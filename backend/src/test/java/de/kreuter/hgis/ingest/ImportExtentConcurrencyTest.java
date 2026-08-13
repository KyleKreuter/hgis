package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.TableCreator;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two imports finishing into the same project at the same moment, and what the project ends
 * up framed around.
 *
 * <p>Finishing an import rolls its layer extents up into {@code project.extent}, which the
 * map reads to pick the view a project opens with. The rollup is a read followed by a
 * write, and two of them overlapping used to lose one: each read the layers it could see,
 * neither could see the other's -- uncommitted -- and the one that committed last wrote its
 * answer over the other's. The project then opened on one of the two layers, with the other
 * somewhere off screen, and nothing short of a further import ever corrected it.
 *
 * <p>Reproducing a race by running it is not reproducing it, so the other import is played
 * by hand on a connection of its own, stopped exactly where the overlap matters: its layer
 * extent written but not committed, and the project row locked. The import under test then
 * has to reach {@code updateProjectExtent} and wait there rather than read past it -- which
 * is the whole difference between taking the row lock before the rollup and taking it
 * after, and the only thing this test is about.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ImportExtentConcurrencyTest {

	private static final int STORAGE_SRID = 25832;

	private static final int TIMEOUT_SECONDS = 20;

	/** The other import's layer, in EPSG:4326 -- the CRS every extent column is pinned to. */
	private static final String HAMBURG_EXTENT = "ST_MakeEnvelope(9.9, 53.5, 10.0, 53.6, 4326)";

	/** This import's single object, in the project's storage CRS: Munich, 500 km away. */
	private static final int MUNICH_EAST = 691_000;
	private static final int MUNICH_NORTH = 5_334_000;

	@Autowired
	private ImportTransactions transactions;

	@Autowired
	private TableCreator tableCreator;

	@Autowired
	private JobService jobService;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("an import finishing beside another one frames the project around both layers")
	void keepsBothLayersInTheProjectExtent() throws Exception {
		Project project = projectRepository.saveAndFlush(
				new Project("Gleichzeitiger Import " + UUID.randomUUID(), null, STORAGE_SRID, "osm"));
		Layer other = createLayer(project, "Hamburg");
		Layer mine = createLayer(project, "München");
		insertPoint(mine.getTableName());

		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "muenchen.geojson");
		jobService.markRunning(job.getId(), mine.getId(), 1L);

		ExecutorService importThread = Executors.newSingleThreadExecutor();
		try (Connection otherImport = dataSource.getConnection()) {
			otherImport.setAutoCommit(false);
			startTheOtherImport(otherImport, project.getId(), other.getId());

			Future<?> finishing = importThread.submit(
					() -> transactions.complete(job.getId(), mine.getId(), STORAGE_SRID, 1, 0));

			awaitBlockedOnTheProjectRow();
			otherImport.commit();

			finishing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		finally {
			importThread.shutdownNow();
		}

		assertThat(projectExtentCovers(project.getId(), HAMBURG_EXTENT))
				.as("the layer the other import committed while this one waited")
				.isTrue();
		assertThat(projectExtentCovers(project.getId(), munichPoint()))
				.as("and the layer this import brought itself")
				.isTrue();

		cleanUp(project, List.of(other, mine), job);
	}

	/**
	 * The other import, stopped mid-flight: its layer extent written, its project row
	 * locked, nothing committed.
	 *
	 * <p>Both halves matter. The lock is what makes the import under test wait; the
	 * uncommitted extent is what makes waiting mean something, since a rollup that reads
	 * before the wait cannot see it and one that reads after can. Two statements on one
	 * connection, so they share a transaction and stay invisible together.
	 */
	private void startTheOtherImport(Connection connection, UUID projectId, UUID layerId)
			throws SQLException {
		try (PreparedStatement extent = connection.prepareStatement(
				"UPDATE gis_meta.layer SET extent = " + HAMBURG_EXTENT + " WHERE id = ?")) {
			extent.setObject(1, layerId);
			extent.executeUpdate();
		}
		try (PreparedStatement lock = connection.prepareStatement(
				"SELECT id FROM gis_meta.project WHERE id = ? FOR UPDATE")) {
			lock.setObject(1, projectId);
			lock.executeQuery().close();
		}
	}

	/**
	 * Waits until a backend is stuck behind a lock, which here can only be the import under
	 * test on the project row -- nothing else in this test holds one.
	 *
	 * <p>A sleep would do the same job on a good day. This waits for the event itself, so a
	 * slow container costs seconds rather than a false green.
	 */
	private void awaitBlockedOnTheProjectRow() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (backendsWaitingForALock() == 0) {
			assertThat(System.nanoTime())
					.as("the finishing import never waited for the project row")
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

	private Layer createLayer(Project project, String name) {
		SourceSchema schema = new SourceSchema(GeometryType.MULTIPOINT, STORAGE_SRID,
				List.of(new SourceField("name", String.class)),
				"UTF-8", SourceSchema.CrsConfidence.DECLARED, 1L);
		return tableCreator.createLayerTable(project, schema, name).layer();
	}

	private void insertPoint(String tableName) {
		jdbc.sql("INSERT INTO %s (geom) VALUES (ST_Multi(ST_SetSRID(ST_MakePoint(:east, :north), :srid)))"
				.formatted(SqlIdentifier.quoteLayerTable(tableName)))
				.param("east", MUNICH_EAST)
				.param("north", MUNICH_NORTH)
				.param("srid", STORAGE_SRID)
				.update();
	}

	private static String munichPoint() {
		return "ST_Transform(ST_SetSRID(ST_MakePoint(%d, %d), %d), 4326)"
				.formatted(MUNICH_EAST, MUNICH_NORTH, STORAGE_SRID);
	}

	private boolean projectExtentCovers(UUID projectId, String geometry) {
		return jdbc.sql("SELECT ST_Covers(extent, " + geometry + ") FROM gis_meta.project WHERE id = :id")
				.param("id", projectId)
				.query(Boolean.class)
				.single();
	}

	private void cleanUp(Project project, List<Layer> layers, Job job) {
		for (Layer layer : layers) {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(layer.getTableName())).update();
			layerRepository.deleteById(layer.getId());
		}
		jdbc.sql("DELETE FROM gis_meta.job WHERE id = :id").param("id", job.getId()).update();
		projectRepository.deleteById(project.getId());
	}
}
