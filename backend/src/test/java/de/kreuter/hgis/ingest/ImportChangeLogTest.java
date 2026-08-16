package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.changelog.ChangeLogAction;
import de.kreuter.hgis.changelog.ChangeLogEntry;
import de.kreuter.hgis.changelog.ChangeLogRepository;
import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.TableCreator.CreatedLayer;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The import is the most common way a layer comes into existence in this project, and
 * until now it left no trace in the write change log at all -- a gap a review found by
 * asking one question of every write path: can this make a layer or a feature exist
 * without a row that says so (CONTRACT.md "Schreibstufe" 1.2, "unabhängig davon, woher
 * der Schreibvorgang kommt").
 *
 * <p>One entry per import, not one per batch -- {@link ImportTransactions#complete}
 * logs both {@code layer.create} and {@code feature.insert} (with the final count)
 * together, once the import has actually succeeded.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ImportChangeLogTest {

	private static final int SRID = 25832;

	@Autowired
	private ImportTransactions transactions;

	@Autowired
	private JobService jobService;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private ChangeLogRepository changeLogRepository;

	@Autowired
	private JdbcClient jdbc;

	@Test
	@DisplayName("a completed import logs layer.create and feature.insert with the final count")
	void completedImportIsLogged() {
		Project project = projectRepository.saveAndFlush(
				new Project("Import-Protokoll " + UUID.randomUUID(), null, SRID, "osm"));
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "test.geojson");
		SourceSchema schema = testSchema();

		CreatedLayer created = transactions.begin(project, job.getId(), schema, "Importiert");
		try {
			transactions.complete(job.getId(), created.layer().getId(), SRID, 3, 0);

			List<ChangeLogEntry> entries = entriesFor(project.getId(), created.layer().getId());
			assertThat(entries).extracting(ChangeLogEntry::getAction)
					.containsExactlyInAnyOrder(ChangeLogAction.LAYER_CREATE, ChangeLogAction.FEATURE_INSERT);

			ChangeLogEntry insertEntry = entries.stream()
					.filter(e -> e.getAction().equals(ChangeLogAction.FEATURE_INSERT))
					.findFirst().orElseThrow();
			assertThat(insertEntry.getAffectedCount()).isEqualTo(3);
			assertThat(insertEntry.getLayerName()).isEqualTo("Importiert");
		}
		finally {
			cleanUp(project, created.layer());
		}
	}

	@Test
	@DisplayName("an import that never reaches complete() logs nothing -- it was never really there")
	void abortedImportLogsNothing() {
		Project project = projectRepository.saveAndFlush(
				new Project("Import-Abbruch " + UUID.randomUUID(), null, SRID, "osm"));
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "test.geojson");
		SourceSchema schema = testSchema();

		CreatedLayer created = transactions.begin(project, job.getId(), schema, "Nie fertig");
		UUID layerId = created.layer().getId();
		String tableName = created.layer().getTableName();

		transactions.compensateAndFail(job.getId(), layerId, tableName, "absichtlich abgebrochen");

		assertThat(entriesFor(project.getId(), layerId)).isEmpty();
		assertThat(layerRepository.findById(layerId)).isEmpty();

		jdbc.sql("DELETE FROM gis_meta.job WHERE id = :id").param("id", job.getId()).update();
		projectRepository.deleteById(project.getId());
	}

	private static SourceSchema testSchema() {
		return new SourceSchema(GeometryType.MULTIPOINT, SRID,
				List.of(new SourceField("name", String.class)),
				"UTF-8", SourceSchema.CrsConfidence.DECLARED, 3L);
	}

	private List<ChangeLogEntry> entriesFor(UUID projectId, UUID layerId) {
		return changeLogRepository.findByProjectIdOrderByOccurredAtDescIdDesc(projectId, PageRequest.of(0, 100))
				.stream()
				.filter(e -> layerId.equals(e.getLayerId()))
				.toList();
	}

	private void cleanUp(Project project, Layer layer) {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(layer.getTableName())).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		jdbc.sql("DELETE FROM gis_meta.job WHERE project_id = :id").param("id", project.getId()).update();
		projectRepository.deleteById(project.getId());
	}
}
