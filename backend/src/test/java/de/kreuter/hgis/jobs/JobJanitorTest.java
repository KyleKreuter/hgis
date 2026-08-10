package de.kreuter.hgis.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.TableCreator;
import de.kreuter.hgis.ingest.PostgisTestcontainersConfiguration;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

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
@Import(PostgisTestcontainersConfiguration.class)
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

	private static SourceSchema minimalSchema() {
		return new SourceSchema(SourceSchema.GeometryType.MULTIPOLYGON, 25832,
				List.of(new SourceField("name", String.class)),
				"UTF-8", SourceSchema.CrsConfidence.DECLARED, 0L);
	}
}
