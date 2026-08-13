package de.kreuter.hgis.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.TableCreator;
import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

/**
 * Simulates the state a crash would leave behind -- a job stuck in RUNNING with its
 * table already created -- and checks that {@link JobJanitor} cleans it up the same way
 * a live import failure would, without going through the real async startup event
 * (which already fired once, harmlessly, before this test seeded any orphaned data).
 *
 * Runs inside Spring's test-managed transaction so table creation, the janitor run and
 * the assertions all see the same connection state, and test data is rolled back
 * automatically afterwards.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class JobJanitorTest {

	@Autowired
	private JobJanitor janitor;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private JobService jobService;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private TableCreator tableCreator;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private EntityManager entityManager;

	/**
	 * Spied so the catalog delete can be made to fail on demand -- the one half of the
	 * cleanup that has to roll back the other. Everything not stubbed runs for real, so the
	 * rest of this class is unaffected.
	 */
	@MockitoSpyBean
	private LayerRepository layerRepository;

	@Test
	void dropsTheHalfWrittenTableAndFailsAnOrphanedRunningJob() {
		Project project = projectRepository.saveAndFlush(new Project("Janitor-Test " + UUID.randomUUID(), null, 25832, "osm"));
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "abgebrochen.geojson");

		// Recreate exactly what phase A of a real import leaves behind: a table, its
		// catalog rows, and the job moved to RUNNING with outputLayerId set.
		TableCreator.CreatedLayer created = tableCreator.createLayerTable(project, minimalSchema(), "Abgebrochener Layer");
		jobService.markRunning(job.getId(), created.layer().getId(), 10L);

		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(Job.Status.RUNNING);

		janitor.cleanUpOne(job.getId());

		Job cleaned = jobRepository.findById(job.getId()).orElseThrow();
		assertThat(cleaned.getStatus()).isEqualTo(Job.Status.FAILED);
		assertThat(cleaned.getMessage()).isNotBlank();
		assertThat(cleaned.getOutputLayerId()).isNull();

		Long layerRows = jdbc.sql("SELECT COUNT(*) FROM gis_meta.layer WHERE id = :id")
				.param("id", created.layer().getId())
				.query(Long.class).single();
		assertThat(layerRows).isZero();

		Long tableExists = jdbc.sql("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = 'gis_data' AND table_name = :tableName
				""")
				.param("tableName", created.layer().getTableName())
				.query(Long.class).single();
		assertThat(tableExists).isZero();
	}

	@Test
	void leavesPendingAndFinishedJobsAlone() {
		Project project = projectRepository.saveAndFlush(new Project("Janitor-Test " + UUID.randomUUID(), null, 25832, "osm"));
		Job pending = jobService.create(project.getId(), Job.Type.IMPORT, "wartend.geojson");

		janitor.cleanUpOrphanedJobs();

		assertThat(jobRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(Job.Status.PENDING);
	}

	@Test
	void removesTheWholeTargetProjectForAnOrphanedDuplicateJob() {
		Project source = projectRepository.saveAndFlush(
				new Project("Duplizierung Quelle " + UUID.randomUUID(), null, 25832, "osm"));
		Project target = projectRepository.saveAndFlush(
				new Project("Duplizierung Ziel " + UUID.randomUUID(), null, 25832, "osm"));
		Job job = jobService.create(source.getId(), Job.Type.DUPLICATE, null);
		jobService.markDuplicateRunning(job.getId(), target.getId(), 0L);

		janitor.cleanUpOne(job.getId());

		entityManager.flush();
		entityManager.clear();
		assertThat(projectRepository.findById(target.getId())).isEmpty();
	}

	/**
	 * The cleanup is one unit of work or it is nothing.
	 *
	 * <p>{@code TableCreator.dropLayer} drops the physical table and then deletes the catalog
	 * row, and it carries no transaction of its own -- it borrows the caller's. The janitor
	 * used to declare one with {@code @Transactional} on a package-private method it called
	 * on itself, which is two reasons for the annotation to do nothing: a proxy sees neither
	 * a self-call nor a non-public method. So in production the two statements ran
	 * separately, and a failure in between left a catalog row whose table was already gone --
	 * a layer that answers every tile request with an error and can never be repaired by
	 * running the janitor again.
	 *
	 * <p>The failure is injected where it actually hurts, on the catalog delete, and the
	 * assertion is that afterwards the table and its row still agree. Runs outside the
	 * class's test transaction on purpose: with one already open, any transaction boundary
	 * would look correct, including the one that was not there.
	 */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("a failure halfway through the cleanup leaves the layer and its table intact")
	void rollsBackTheWholeCleanUpWhenTheCatalogDeleteFails() {
		Project project = projectRepository.saveAndFlush(
				new Project("Janitor-Rollback " + UUID.randomUUID(), null, 25832, "osm"));
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "abgebrochen.geojson");
		TableCreator.CreatedLayer created =
				tableCreator.createLayerTable(project, minimalSchema(), "Halb geschriebener Layer");
		jobService.markRunning(job.getId(), created.layer().getId(), 10L);

		UUID layerId = created.layer().getId();
		String tableName = created.layer().getTableName();
		doThrow(new IllegalStateException("Die Katalogzeile lässt sich nicht löschen"))
				.when(layerRepository).deleteById(layerId);

		assertThatThrownBy(() -> janitor.cleanUpOne(job.getId()))
				.isInstanceOf(IllegalStateException.class);

		assertThat(tableExists(tableName))
				.as("the DROP TABLE must roll back with the catalog delete it belongs to")
				.isTrue();
		assertThat(layerCatalogRows(layerId)).isEqualTo(1);
		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
				.as("nothing happened at all, so the job is still there to be cleaned up")
				.isEqualTo(Job.Status.RUNNING);

		Mockito.reset(layerRepository);
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		jobRepository.deleteById(job.getId());
		layerRepository.deleteById(layerId);
		projectRepository.deleteById(project.getId());
	}

	private boolean tableExists(String tableName) {
		return jdbc.sql("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = 'gis_data' AND table_name = :tableName
				""")
				.param("tableName", tableName)
				.query(Long.class).single() == 1;
	}

	private long layerCatalogRows(UUID layerId) {
		return jdbc.sql("SELECT COUNT(*) FROM gis_meta.layer WHERE id = :id")
				.param("id", layerId)
				.query(Long.class).single();
	}

	private static SourceSchema minimalSchema() {
		return new SourceSchema(GeometryType.MULTIPOLYGON, 25832,
				List.of(new SourceField("name", String.class)),
				"UTF-8", SourceSchema.CrsConfidence.DECLARED, 0L);
	}
}
