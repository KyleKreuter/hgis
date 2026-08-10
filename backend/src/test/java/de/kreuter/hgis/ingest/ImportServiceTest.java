package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * End to end tests of the write engine: {@link ImportService}, {@link ImportTransactions},
 * {@link de.kreuter.hgis.common.TableCreator} and {@link FeatureWriter} together, driven
 * by {@link FakeSourceReader} since track A's real readers do not exist yet.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ImportServiceTest {

	@Autowired
	private ImportService importService;

	@Autowired
	private JobService jobService;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository layerFieldRepository;

	@Autowired
	private JdbcClient jdbc;

	private Project project;

	@BeforeEach
	void createProject() {
		project = projectRepository.saveAndFlush(new Project("Import-Test " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@Test
	@DisplayName("5000 features: table, columns and feature_count all come out right")
	void importsBulkFeatures() {
		FakeSourceReader reader = FakeSourceReader.bulkPolygons(5000, project.getSrid());
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "bulk.geojson");

		importService.runImport(job.getId(), project.getId(), reader, "Bulk Layer", null);

		JobDtos.Response result = jobService.get(job.getId());
		assertThat(result.status()).isEqualTo("SUCCEEDED");
		assertThat(result.processedCount()).isEqualTo(5000);
		assertThat(result.skippedCount()).isZero();
		assertThat(result.outputLayerId()).isNotNull();

		Layer layer = layerRepository.findById(result.outputLayerId()).orElseThrow();
		assertThat(layer.getFeatureCount()).isEqualTo(5000);
		assertThat(layer.getGeometryType()).isEqualTo("MULTIPOLYGON");
		assertThat(layer.getSrid()).isEqualTo(project.getSrid());
		assertThat(layer.getExtent()).as("extent must be computed and stored as EPSG:4326").isNotNull();
		assertThat(layer.getExtent().getSRID()).isEqualTo(4326);
		assertThat(layer.getDataVersion()).as("every batch write bumps data_version").isGreaterThan(1);

		var fields = layerFieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId());
		assertThat(fields).extracting(LayerField::getColumnName).containsExactly("name", "area");
		assertThat(fields).extracting(LayerField::getDataType).containsExactly("text", "double precision");

		long rowCount = jdbc.sql("SELECT COUNT(*) FROM " + SqlIdentifier.quoteLayerTable(layer.getTableName()))
				.query(Long.class).single();
		assertThat(rowCount).isEqualTo(5000L);

		assertThat(reader.isClosed()).as("the reader must be closed once the import is done").isTrue();
	}

	@Test
	@DisplayName("umlauts normalise and a field literally called 'geom' does not collide with the geometry column")
	void normalisesUmlautsAndAvoidsGeomCollision() {
		FakeSourceReader reader = FakeSourceReader.umlautAndReservedNames(project.getSrid());
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "umlaute.geojson");

		importService.runImport(job.getId(), project.getId(), reader, "Umlaut Layer", null);

		JobDtos.Response result = jobService.get(job.getId());
		assertThat(result.status()).isEqualTo("SUCCEEDED");

		Layer layer = layerRepository.findById(result.outputLayerId()).orElseThrow();
		var fields = layerFieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId());

		assertThat(fields).extracting(LayerField::getSourceName)
				.containsExactly("Gebäudehöhe", "geom", "Straße");
		assertThat(fields).extracting(LayerField::getColumnName)
				.containsExactly("gebaeudehoehe", "geom_1", "strasse");
		assertThat(fields.stream().map(LayerField::getColumnName)).doesNotContain("geom");

		long rowCount = jdbc.sql("SELECT COUNT(*) FROM " + SqlIdentifier.quoteLayerTable(layer.getTableName()))
				.query(Long.class).single();
		assertThat(rowCount).isEqualTo(3L);
	}

	@Test
	@DisplayName("a failure partway through leaves neither the table nor catalog rows behind, and fails the job")
	void compensatesFullyOnMidImportFailure() {
		long tablesBefore = countGisDataTables();

		// 1500 good features means the first batch of 1000 already committed successfully
		// before the second batch, containing the bad row, aborts -- proving the whole
		// table is dropped even though part of it was, briefly, correctly written.
		FakeSourceReader reader = FakeSourceReader.failingMidway(1500, project.getSrid());
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "kaputt.geojson");

		importService.runImport(job.getId(), project.getId(), reader, "Kaputter Layer", null);

		JobDtos.Response result = jobService.get(job.getId());
		assertThat(result.status()).isEqualTo("FAILED");
		assertThat(result.message()).isNotBlank();
		assertThat(result.outputLayerId()).as("ON DELETE SET NULL clears the reference once the layer is gone")
				.isNull();

		assertThat(countLayerCatalogRows(project.getId())).as("no catalog row survives the compensation").isZero();
		assertThat(countGisDataTables()).as("no table survives the compensation").isEqualTo(tablesBefore);
	}

	@Test
	@DisplayName("exceeding the 5% skip ratio fails the job even though every processed row was written correctly")
	void failsWhenTooManyFeaturesAreSkipped() {
		FakeSourceReader reader = FakeSourceReader.withSkippedFeatures(10, 5, project.getSrid()); // 33% skipped
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "zu-viele-fehler.geojson");

		importService.runImport(job.getId(), project.getId(), reader, "Zu viele Fehler", null);

		JobDtos.Response result = jobService.get(job.getId());
		assertThat(result.status()).isEqualTo("FAILED");
		assertThat(result.message()).contains("übersprungen");
		assertThat(countLayerCatalogRows(project.getId())).isZero();
	}

	@Test
	@DisplayName("a single Polygon is promoted with ST_Multi and fits the MultiPolygon column")
	void promotesSingleGeometryViaStMulti() {
		FakeSourceReader reader = FakeSourceReader.singlePolygon(project.getSrid());
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "single.geojson");

		importService.runImport(job.getId(), project.getId(), reader, "Single Geom Layer", null);

		JobDtos.Response result = jobService.get(job.getId());
		assertThat(result.status()).isEqualTo("SUCCEEDED");

		Layer layer = layerRepository.findById(result.outputLayerId()).orElseThrow();
		String geometryType = jdbc.sql(
				"SELECT ST_GeometryType(geom) FROM " + SqlIdentifier.quoteLayerTable(layer.getTableName())
						+ " LIMIT 1")
				.query(String.class).single();

		assertThat(geometryType).isEqualTo("ST_MultiPolygon");
	}

	private long countGisDataTables() {
		return jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'gis_data'")
				.query(Long.class).single();
	}

	// Deliberately not layerRepository.findByProjectIdOrderByZIndexAscCreatedAtAsc(...):
	// that derived query fails at runtime on this Hibernate/Spring Data version
	// ("Could not resolve attribute 'ZIndex' of Layer"), independent of anything in this
	// track. LayerRepository is off limits here (owned by the foundational track), so
	// this is a plain read instead -- worth flagging upstream, not fixing in this branch.
	private long countLayerCatalogRows(UUID projectId) {
		return jdbc.sql("SELECT COUNT(*) FROM gis_meta.layer WHERE project_id = :projectId")
				.param("projectId", projectId)
				.query(Long.class).single();
	}
}
