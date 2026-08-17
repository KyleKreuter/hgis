package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.dto.LayerDtos;
import de.kreuter.hgis.common.ConflictException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code restore} and {@code purge} racing on the same trashed layer, and two {@code
 * purge} calls racing on the same one -- a review found both could each answer success
 * while the layer ended up destroyed underneath the caller who was just told it was
 * back. Reproducing a race by running it proves nothing except that this run happened
 * not to lose; the other side of each race is played by hand instead, on a raw
 * connection of its own, stopped exactly where the overlap matters -- the same
 * technique {@code ImportExtentConcurrencyTest} uses for the same reason.
 *
 * <p>{@link LayerRepository#findByIdForUpdate} is what these tests are about: without
 * it, the operation under test never waits at all, and both scenarios below finish
 * with a false success instead of a proper conflict.
 *
 * <p>{@link #plainReadIsNotBlockedByAConcurrentPurgesLock} proves the opposite side of the
 * same lock: it must guard writers against each other without becoming something an
 * ordinary read waits on too. {@code LayerService#get} -- the path {@code GET
 * /api/layers/{id}} takes -- goes through the plain, unlocked {@code findById} on
 * purpose, and PostgreSQL's MVCC reads never need the row lock to see a consistent
 * snapshot. That stays true only as long as nobody "hardens" the guard from {@code
 * SELECT ... FOR UPDATE} to something stronger later for some case that seems to need
 * it -- exactly the kind of change that would leave a read stalled behind an in-flight
 * purge without a single existing test noticing.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LayerTrashRaceConcurrencyTest {

	private static final int TIMEOUT_SECONDS = 20;

	@Autowired
	private LayerService layerService;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private DataSource dataSource;

	private Project project;
	private Layer layer;
	private String tableName;

	@BeforeEach
	void createTrashedLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Papierkorb-Wettlauf " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(SqlIdentifier.quoteLayerTable(tableName))).update();
		layer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Wettlauf", tableName, "MULTIPOLYGON", 25832));

		layerService.delete(layer.getId(), "erster-client");
	}

	@AfterEach
	void cleanUp() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	@Test
	@DisplayName("restore committing first makes the racing purge see a conflict, not a false 204")
	void restoreWinningMakesPurgeConflict() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection restoreConnection = dataSource.getConnection()) {
			restoreConnection.setAutoCommit(false);
			// Restore's own effect, played by hand: the row lock is taken here, and the
			// state change sits uncommitted -- exactly where a real restore transaction
			// would be between its own UPDATE and its commit.
			restoreFirst(restoreConnection, layer.getId());

			Future<Throwable> purgeOutcome = pool.submit(() -> {
				try {
					layerService.purge(layer.getId(), "zweiter-client");
					return null;
				}
				catch (RuntimeException ex) {
					return ex;
				}
			});

			awaitBlockedOnTheLayerRow();
			restoreConnection.commit();

			Throwable outcome = purgeOutcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertThat(outcome)
					.as("purge must not silently succeed against a layer restore just brought back")
					.isInstanceOf(ConflictException.class);
		}
		finally {
			pool.shutdownNow();
		}

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.isTrashed()).as("restore won the race, so the layer is not trashed any more").isFalse();

		Boolean tableStillExists = jdbc.sql("SELECT to_regclass('gis_data.' || :tableName) IS NOT NULL")
				.param("tableName", tableName)
				.query(Boolean.class)
				.single();
		assertThat(tableStillExists).as("the payload table must survive a purge that lost the race").isTrue();
	}

	@Test
	@DisplayName("two racing purges: the second sees the layer gone, not an unhandled optimistic-locking 500")
	void secondPurgeSeesNotFoundInsteadOfCrashing() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection firstPurge = dataSource.getConnection()) {
			firstPurge.setAutoCommit(false);
			// The first purge's own effect, played by hand: row locked, the layer already
			// gone from its perspective, nothing committed yet.
			deleteLayerRow(firstPurge, layer.getId());

			Future<Throwable> secondOutcome = pool.submit(() -> {
				try {
					layerService.purge(layer.getId(), "zweiter-client");
					return null;
				}
				catch (RuntimeException ex) {
					return ex;
				}
			});

			awaitBlockedOnTheLayerRow();
			firstPurge.commit();

			Throwable outcome = secondOutcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertThat(outcome)
					.as("the row is genuinely gone by the time the lock releases -- a clean 404, "
							+ "not Hibernate's ObjectOptimisticLockingFailureException surfacing as 500")
					.isInstanceOf(NotFoundException.class);
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(layerRepository.findById(layer.getId())).isEmpty();
	}

	@Test
	@DisplayName("without the row lock, purging a layer that is not trashed is still a plain, immediate conflict")
	void purgeOnAFreshLayerStillConflictsWithoutAnyLocking() {
		Layer other = layerRepository.saveAndFlush(
				new Layer(UUID.randomUUID(), project, "Nicht im Papierkorb",
						SqlIdentifier.tableName(UUID.randomUUID()), "MULTIPOLYGON", 25832));
		assertThatThrownBy(() -> layerService.purge(other.getId(), null))
				.isInstanceOf(ConflictException.class);
		layerRepository.delete(other);
	}

	@Test
	@DisplayName("a plain read of a layer being purged does not wait on the purge's row lock")
	void plainReadIsNotBlockedByAConcurrentPurgesLock() throws Exception {
		try (Connection purgeConnection = dataSource.getConnection()) {
			purgeConnection.setAutoCommit(false);
			// Plays purge's own effect by hand, exactly like the two races above: the row
			// lock is taken here and held open, uncommitted.
			try (PreparedStatement lock = purgeConnection.prepareStatement(
					"SELECT * FROM gis_meta.layer WHERE id = ? FOR UPDATE")) {
				lock.setObject(1, layer.getId());
				lock.executeQuery();
			}

			long start = System.nanoTime();
			// An ordinary read -- LayerService#get, the same path GET /api/layers/{id}
			// takes -- while the purge lock above is still held and uncommitted.
			LayerDtos.Detail detail = layerService.get(layer.getId());
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			assertThat(detail.name()).isEqualTo("Wettlauf");
			assertThat(elapsedMs)
					.as("a plain read must return immediately, not wait for the purge lock to release")
					.isLessThan(2000);

			purgeConnection.rollback();
		}
	}

	// --- the other side of each race, played on a connection of its own ----------------

	private void restoreFirst(Connection connection, UUID layerId) throws Exception {
		try (PreparedStatement update = connection.prepareStatement(
				"UPDATE gis_meta.layer SET deleted_at = NULL, deleted_by = NULL WHERE id = ?")) {
			update.setObject(1, layerId);
			update.executeUpdate();
		}
	}

	private void deleteLayerRow(Connection connection, UUID layerId) throws Exception {
		try (PreparedStatement delete = connection.prepareStatement(
				"DELETE FROM gis_meta.layer WHERE id = ?")) {
			delete.setObject(1, layerId);
			delete.executeUpdate();
		}
	}

	/**
	 * Waits until a backend is stuck behind a lock -- nothing else in this test holds
	 * one, so it can only be the operation under test on the layer row. Polling the
	 * event itself rather than sleeping a fixed amount is what keeps this reliable on a
	 * slow container instead of racing the assertion below it.
	 */
	private void awaitBlockedOnTheLayerRow() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (backendsWaitingForALock() == 0) {
			assertThat(System.nanoTime())
					.as("the operation under test never waited for the layer row's lock")
					.isLessThan(deadline);
			TimeUnit.MILLISECONDS.sleep(20);
		}
	}

	private long backendsWaitingForALock() {
		return jdbc.sql("""
				SELECT count(*) FROM pg_stat_activity
				WHERE datname = current_database() AND wait_event_type = 'Lock'
				""")
				.query(Long.class)
				.single();
	}
}
