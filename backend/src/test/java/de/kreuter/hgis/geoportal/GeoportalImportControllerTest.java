package de.kreuter.hgis.geoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.geoportal.dto.GeoportalDtos;
import de.kreuter.hgis.jobs.JobRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * End to end: an HTTP request against the Geoportal import endpoint, through the real
 * {@code ImportService}/{@code ImportTransactions}/{@code TableCreator} pipeline, into real
 * PostGIS -- what actually proves decisions E1 (technical name stays filterable), E6 (the
 * id field travels along and gets an index) and the layer provenance (phase 23.7) all
 * arrive correctly at the far end. {@link GeoportalDatasetService} is mocked (its own
 * catalog logic is {@link CatalogLoaderTest}'s job); the OGC API Features service itself is
 * {@link MockRestServiceServer}, bound to the same {@code geoportalRestClient} bean the
 * application wires into the reader, via the {@code @Primary} override below -- no test
 * here touches the network.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GeoportalImportControllerTest {

	private static final String API_URL = "https://api.hamburg.de/datasets/v1/strassenbaumkataster";
	private static final String COLLECTION = "strassenbaumkataster_hh";
	private static final String DATASET_ID = "strassenbaumkataster/strassenbaumkataster_hh";

	private static final String COLLECTION_INFO = """
			{"title":"Straßenbaumkataster Hamburg","itemCount":2,
			 "crs":["http://www.opengis.net/def/crs/OGC/1.3/CRS84","http://www.opengis.net/def/crs/EPSG/0/25832"],
			 "storageCrs":"http://www.opengis.net/def/crs/EPSG/0/25832"}
			""";

	private static final String QUERYABLES = """
			{"properties": {
			  "gid": {"title":"gid","type":"integer","readOnly":true,"x-ogc-role":"id"},
			  "kronendurchmesser_z": {"title":"kronendurchmesser_z","type":"string"}
			}}
			""";

	private static final String ITEMS_PAGE = """
			{"type":"FeatureCollection","numberReturned":2,"numberMatched":2,"features":[
			  {"type":"Feature","id":1,"geometry":{"type":"MultiPoint","coordinates":[[565000,5931000]]},
			   "properties":{"kronendurchmesser_z":"5 m"}},
			  {"type":"Feature","id":2,"geometry":{"type":"MultiPoint","coordinates":[[565100,5931100]]},
			   "properties":{"kronendurchmesser_z":"6 m"}}
			]}
			""";

	@TestConfiguration
	static class MockGeoportalHttpConfig {

		@Bean
		RestClient.Builder mockableGeoportalRestClientBuilder() {
			return RestClient.builder();
		}

		@Bean
		MockRestServiceServer mockGeoportalServer(RestClient.Builder mockableGeoportalRestClientBuilder) {
			return MockRestServiceServer.bindTo(mockableGeoportalRestClientBuilder).build();
		}

		/** {@code @Primary} so every {@code RestClient} injection point in the context gets this one
		 *  instead of {@code GeoportalHttpClientConfig}'s real, network-facing bean. */
		@Bean
		@Primary
		RestClient testGeoportalRestClient(RestClient.Builder mockableGeoportalRestClientBuilder,
				MockRestServiceServer mockGeoportalServer) {
			return mockableGeoportalRestClientBuilder.build();
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MockRestServiceServer mockGeoportalServer;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository layerFieldRepository;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private GeoportalDatasetService datasetService;

	private final ObjectMapper mapper = new ObjectMapper();

	private Project project;

	@BeforeEach
	void setUp() {
		mockGeoportalServer.reset();
		project = projectRepository.saveAndFlush(new Project("Geoportal-Test " + UUID.randomUUID(), null, 25832, "osm"));
	}

	private void expectSuccessfulSchemaAndItemsFetch() {
		mockGeoportalServer.expect(requestTo(org.hamcrest.Matchers.containsString("/collections/" + COLLECTION + "?")))
				.andExpect(method(org.springframework.http.HttpMethod.GET))
				.andRespond(withSuccess(COLLECTION_INFO, MediaType.APPLICATION_JSON));
		mockGeoportalServer.expect(requestTo(org.hamcrest.Matchers.containsString("/queryables")))
				.andRespond(withSuccess(QUERYABLES, MediaType.APPLICATION_JSON));
		mockGeoportalServer.expect(requestTo(org.hamcrest.Matchers.containsString("/items")))
				.andRespond(withSuccess(ITEMS_PAGE, MediaType.APPLICATION_JSON)
						.header("Content-Crs", "<http://www.opengis.net/def/crs/EPSG/0/25832>"));
	}

	@Test
	@DisplayName("a full import: German display name, technical column name, id-field index and provenance all land correctly")
	void importsADatasetWithCorrectColumnNamingAndProvenance() throws Exception {
		GeoportalCatalogEntry entry = new GeoportalCatalogEntry(
				DATASET_ID, "Straßenbaumkataster Hamburg", "FEATURES", "BUKEA",
				"Freie und Hansestadt Hamburg, Behörde für Umwelt, Klima, Energie und Agrarwirtschaft",
				"Umwelt", "https://metaver.de/trefferanzeige?docuuid=x", "https://registry.gdi-de.org/id/de.hh/x",
				API_URL, COLLECTION, Map.of("kronendurchmesser_z", "Kronendurchmesser"));
		given(datasetService.requireImportable(DATASET_ID)).willReturn(entry);
		expectSuccessfulSchemaAndItemsFetch();

		GeoportalDtos.ImportRequest request = new GeoportalDtos.ImportRequest(DATASET_ID, null, null, null);

		String jobId = mvc.perform(post("/api/projects/" + project.getId() + "/geoportal-imports")
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(request)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.type").value("IMPORT"))
				.andReturn().getResponse().getContentAsString();

		UUID id = UUID.fromString(mapper.readTree(jobId).get("id").asString());

		awaitSucceeded(id);

		UUID layerId = jobRepository.findById(id).orElseThrow().getOutputLayerId();
		Layer layer = layerRepository.findById(layerId).orElseThrow();
		assertThat(layer.getName()).isEqualTo("Straßenbaumkataster Hamburg");
		assertThat(layer.getFeatureCount()).isEqualTo(2);

		// Decision E1: source_name shows the German label, column_name stays the technical name.
		var fields = layerFieldRepository.findByLayerIdOrderByOrdinalAsc(layerId);
		LayerField gidField = fields.stream().filter(f -> f.getColumnName().equals("gid")).findFirst().orElseThrow();
		assertThat(gidField.getSourceName()).isEqualTo("gid");
		LayerField kronendurchmesserField = fields.stream()
				.filter(f -> f.getSourceName().equals("Kronendurchmesser")).findFirst().orElseThrow();
		assertThat(kronendurchmesserField.getColumnName()).isEqualTo("kronendurchmesser_z");

		// Decision E6: a non-unique index exists on the id field's own column.
		String indexName = jdbc.sql("""
				SELECT indexname FROM pg_indexes
				WHERE schemaname = 'gis_data' AND tablename = :table AND indexname LIKE '%gid_idx'
				""")
				.param("table", layer.getTableName())
				.query(String.class)
				.optional()
				.orElse(null);
		assertThat(indexName).as("a non-unique index on the id-field column must exist").isNotNull();

		// CONTRACT.md phase 23.7: provenance landed on the layer.
		assertThat(layer.getSourceAttribution())
				.isEqualTo("Freie und Hansestadt Hamburg, Behörde für Umwelt, Klima, Energie und Agrarwirtschaft");
		assertThat(layer.getSourceLicenseName()).isEqualTo(GeoportalLicense.NAME);
		assertThat(layer.getSourceDatasetId()).isEqualTo(DATASET_ID);
		assertThat(layer.getSourceFeatureIdField()).isEqualTo("gid");
		assertThat(layer.getSourceFetchedAt()).isNotNull();

		// CONTRACT.md 11.7 (clarified): "source" must reach the client from the *list*
		// endpoint, not only from the single-layer detail -- the map and the layer tree
		// both render from LayerDtos.Summary, and a licence notice that only arrived with
		// a detail request would never reach the bottom-right attribution.
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.get("/api/projects/" + project.getId() + "/layers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].source.attribution")
						.value("Freie und Hansestadt Hamburg, Behörde für Umwelt, Klima, Energie und Agrarwirtschaft"))
				.andExpect(jsonPath("$[0].source.licenseName").value(GeoportalLicense.NAME))
				.andExpect(jsonPath("$[0].source.datasetId").value(DATASET_ID))
				.andExpect(jsonPath("$[0].source.featureIdField").value("gid"));

		mockGeoportalServer.verify();
	}

	/** The import runs asynchronously, same reasoning as {@code EndToEndTest#awaitJob}. */
	private void awaitSucceeded(UUID jobId) throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			var job = jobRepository.findById(jobId).orElseThrow();
			String status = job.getStatus().name();
			if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
				assertThat(status).as("job message: " + job.getMessage()).isEqualTo("SUCCEEDED");
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Geoportal-Import wurde binnen 10 s nicht fertig");
	}

	@Test
	@DisplayName("a WMS-only dataset is rejected with 400, before any job is created (CONTRACT.md phase 23 scope)")
	void wmsOnlyDatasetIsRejected() throws Exception {
		given(datasetService.requireImportable(eq("md:x")))
				.willThrow(new de.kreuter.hgis.common.BadRequestException(
						"Der Datensatz 'ALKIS Flurstücke' bietet keinen Objektzugang über OGC API Features"));

		GeoportalDtos.ImportRequest request = new GeoportalDtos.ImportRequest("md:x", null, null, null);

		mvc.perform(post("/api/projects/" + project.getId() + "/geoportal-imports")
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("an unknown project is a 404")
	void unknownProjectIsNotFound() throws Exception {
		GeoportalDtos.ImportRequest request = new GeoportalDtos.ImportRequest(DATASET_ID, null, null, null);

		mvc.perform(post("/api/projects/" + UUID.randomUUID() + "/geoportal-imports")
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(request)))
				.andExpect(status().isNotFound());
	}
}
