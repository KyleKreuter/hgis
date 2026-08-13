package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.TableCreator;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
	@DisplayName("each import stacks on top of the previous one instead of sharing z_index 0")
	void assignsAnIncreasingZIndexPerImport() {
		List<Layer> imported = new ArrayList<>();
		for (String name : List.of("Erster", "Zweiter", "Dritter")) {
			Job job = jobService.create(project.getId(), Job.Type.IMPORT, name + ".geojson");
			importService.runImport(job.getId(), project.getId(),
					FakeSourceReader.singlePolygon(project.getSrid()), name, null);
			imported.add(layerRepository.findById(jobService.get(job.getId()).outputLayerId()).orElseThrow());
		}

		// Ties would leave the order undefined, and the two consumers break a tie in
		// opposite directions: the layer tree sorts descending, the map moves ascending.
		assertThat(imported).extracting(Layer::getZIndex).containsExactly(0, 1, 2);
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

	/**
	 * The two failure paths that never reach phase B's own try-with-resources: the project
	 * is gone by the time the import runs on its background thread, or table creation
	 * itself fails. Forcing either one against the real database reliably would mean
	 * depending on internals (a concrete DDL error, a precisely timed project deletion)
	 * this test has no business knowing about, so {@link ImportTransactions} is mocked here
	 * instead -- unlike every other test in this class, what is being verified is an
	 * interaction (the reader gets closed) rather than a database state.
	 */
	@Test
	@DisplayName("the project is gone by the time the import runs: the reader is still closed")
	void closesTheReaderWhenTheProjectIsGone() {
		ProjectRepository noSuchProject = mock(ProjectRepository.class);
		when(noSuchProject.findById(any())).thenReturn(Optional.empty());
		ImportTransactions transactions = mock(ImportTransactions.class);
		ImportService serviceUnderTest = new ImportService(transactions, noSuchProject);

		FakeSourceReader reader = FakeSourceReader.singlePolygon(project.getSrid());
		UUID jobId = UUID.randomUUID();

		serviceUnderTest.runImport(jobId, UUID.randomUUID(), reader, "Layer", null);

		assertThat(reader.isClosed())
				.as("the reader must be closed even when the project no longer exists")
				.isTrue();
		verify(transactions).failBeforeTableExists(eq(jobId), any());
		verify(transactions, never()).begin(any(), any(), any(), any());
	}

	@Test
	@DisplayName("table creation fails: the reader is still closed")
	void closesTheReaderWhenTableCreationFails() {
		ProjectRepository stubbedProjectRepository = mock(ProjectRepository.class);
		UUID projectId = UUID.randomUUID();
		when(stubbedProjectRepository.findById(projectId)).thenReturn(Optional.of(project));

		ImportTransactions transactions = mock(ImportTransactions.class);
		when(transactions.begin(any(), any(), any(), any()))
				.thenThrow(new RuntimeException("Tabelle konnte nicht angelegt werden"));
		ImportService serviceUnderTest = new ImportService(transactions, stubbedProjectRepository);

		FakeSourceReader reader = FakeSourceReader.singlePolygon(project.getSrid());
		UUID jobId = UUID.randomUUID();

		serviceUnderTest.runImport(jobId, projectId, reader, "Layer", null);

		assertThat(reader.isClosed())
				.as("the reader must be closed even when the table could not be created")
				.isTrue();
		verify(transactions).failBeforeTableExists(eq(jobId), any());
		verify(transactions, never()).complete(any(), any(), anyInt(), anyLong(), anyLong());
	}

	/**
	 * The last promise the class makes: {@code runImport} never throws.
	 *
	 * <p>Until now the compensation itself could break it. A lock timeout or a lost
	 * connection while dropping the half-written table took the exception straight out of
	 * {@code runImport} -- on a background thread, where nothing catches it -- and the job
	 * stayed RUNNING for good: polled by a progress bar that never moves, and out of reach
	 * of the janitor, which only ever runs at startup.
	 *
	 * <p>So the two are separated: cleaning up may fail, reporting must not. The job ends
	 * FAILED either way, and its message says the table stayed behind, which is exactly what
	 * {@code JobJanitor} lists on the next start.
	 */
	@Test
	@DisplayName("a compensation that fails itself still ends the job instead of leaving it RUNNING")
	void reportsTheFailureEvenWhenTheCompensationFails() {
		UUID projectId = UUID.randomUUID();
		ProjectRepository stubbedProjectRepository = mock(ProjectRepository.class);
		when(stubbedProjectRepository.findById(projectId)).thenReturn(Optional.of(project));

		Layer halfWritten = new Layer(UUID.randomUUID(), project, "Halb geschrieben",
				SqlIdentifier.tableName(UUID.randomUUID()), "MULTIPOLYGON", project.getSrid());
		ImportTransactions transactions = mock(ImportTransactions.class);
		when(transactions.begin(any(), any(), any(), any()))
				.thenReturn(new TableCreator.CreatedLayer(halfWritten, List.of()));
		doThrow(new RuntimeException("Der Abschluss ist fehlgeschlagen"))
				.when(transactions).complete(any(), any(), anyInt(), anyLong(), anyLong());
		doThrow(new RuntimeException("Die Tabelle lässt sich nicht löschen"))
				.when(transactions).compensateAndFail(any(), any(), any(), any());

		ImportService serviceUnderTest = new ImportService(transactions, stubbedProjectRepository);
		UUID jobId = UUID.randomUUID();

		serviceUnderTest.runImport(jobId, projectId,
				FakeSourceReader.singlePolygon(project.getSrid()), "Layer", null);

		ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
		verify(transactions).failBeforeTableExists(eq(jobId), reason.capture());
		assertThat(reason.getValue())
				.as("the reason names the original failure and says the table stayed behind")
				.contains("Der Abschluss ist fehlgeschlagen")
				.contains("Tabelle des Layers blieb zurück");
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
