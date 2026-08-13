package de.kreuter.hgis.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The wire side of CONTRACT.md section 12: the two paths, the two response shapes and the
 * status code every listed error carries.
 *
 * <p>{@link SplitMergeServiceTest} proves what the operations do to the data. This proves
 * what a client sees -- which is a separate promise, because both are reached through
 * {@code ProblemDetailAdvice} and a wrongly typed exception would turn a documented 409
 * into a 500 without a single service test noticing.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SplitMergeControllerTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String SQUARE = """
			{"type":"Polygon","coordinates":[[[9.98,53.54],[9.99,53.54],[9.99,53.55],[9.98,53.55],[9.98,53.54]]]}
			""";

	private static final String SQUARE_EAST = """
			{"type":"Polygon","coordinates":[[[9.99,53.54],[10.0,53.54],[10.0,53.55],[9.99,53.55],[9.99,53.54]]]}
			""";

	private static final String CUT = """
			{"type":"LineString","coordinates":[[9.9825,53.53],[9.9825,53.56]]}
			""";

	private static final String CUT_ELSEWHERE = """
			{"type":"LineString","coordinates":[[8.5,52.0],[8.6,52.1]]}
			""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository fieldRepository;

	private Project project;
	private Layer layer;

	@BeforeEach
	void createLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Teilen-HTTP " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		String tableName = SqlIdentifier.tableName(layerId);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom    geometry(MultiPolygon, 25832) NOT NULL,
				    strasse text
				)
				""".formatted(SqlIdentifier.quoteLayerTable(tableName))).update();

		layer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Teilbar", tableName, "MULTIPOLYGON", 25832));
		fieldRepository.saveAndFlush(new LayerField(layer, "Straße", "strasse", "text", 0));
	}

	@AfterEach
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(layer.getTableName())).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	private long insert(String geoJson, String strasse) {
		return jdbc.sql("INSERT INTO " + SqlIdentifier.quoteLayerTable(layer.getTableName())
						+ " (geom, strasse) VALUES (ST_Multi(ST_Transform("
						+ "ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326), 25832)), :s) RETURNING fid")
				.param("g", geoJson)
				.param("s", strasse)
				.query(Long.class)
				.single();
	}

	private String rowVersion(long fid) {
		return jdbc.sql("SELECT xmin::text FROM " + SqlIdentifier.quoteLayerTable(layer.getTableName())
						+ " WHERE fid = :fid")
				.param("fid", fid)
				.query(String.class)
				.single();
	}

	private MockHttpServletResponse split(long fid, String body) throws Exception {
		return mockMvc.perform(post("/api/layers/{layerId}/features/{fid}/split", layer.getId(), fid)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn()
				.getResponse();
	}

	private MockHttpServletResponse merge(String body) throws Exception {
		return mockMvc.perform(post("/api/layers/{layerId}/features/merge", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn()
				.getResponse();
	}

	private static JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("split answers with the original's fid first and the layer's new data version")
	void splitReturnsTheContractShape() throws Exception {
		long fid = insert(SQUARE, "Alte Gasse");
		long versionBefore = layerRepository.findById(layer.getId()).orElseThrow().getDataVersion();

		MockHttpServletResponse response = split(fid,
				"{\"line\": " + CUT + ", \"rowVersion\": \"" + rowVersion(fid) + "\"}");

		assertThat(response.getStatus()).isEqualTo(200);
		JsonNode body = bodyOf(response);
		assertThat(body.get("fids").size()).isEqualTo(2);
		assertThat(body.get("fids").get(0).asLong()).isEqualTo(fid);
		assertThat(body.get("dataVersion").asLong()).isGreaterThan(versionBefore);
	}

	@Test
	@DisplayName("a line beside the object is a 400 carrying the contract's wording")
	void splitWithoutAnIntersectionIsBadRequest() throws Exception {
		long fid = insert(SQUARE, null);

		MockHttpServletResponse response = split(fid, "{\"line\": " + CUT_ELSEWHERE + "}");

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(bodyOf(response).get("detail").asString()).isEqualTo("Die Linie teilt das Objekt nicht.");
	}

	@Test
	@DisplayName("a stale row version is a 409 carrying the current row")
	void splitWithAStaleRowVersionIsConflict() throws Exception {
		long fid = insert(SQUARE, null);

		MockHttpServletResponse response = split(fid,
				"{\"line\": " + CUT + ", \"rowVersion\": \"1\"}");

		assertThat(response.getStatus()).isEqualTo(409);
		JsonNode body = bodyOf(response);
		assertThat(body.get("current").get("row_version").asString()).isEqualTo(rowVersion(fid));
	}

	@Test
	void splitOfAMissingFeatureIsNotFound() throws Exception {
		assertThat(split(999_999, "{\"line\": " + CUT + "}").getStatus()).isEqualTo(404);
	}

	@Test
	@DisplayName("a split without a line is rejected by validation, not by PostGIS")
	void splitWithoutALineIsBadRequest() throws Exception {
		long fid = insert(SQUARE, null);

		MockHttpServletResponse response = split(fid, "{}");

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(bodyOf(response).get("errors").get("line").asString()).isEqualTo("Teilungslinie fehlt");
	}

	@Test
	@DisplayName("merge answers with the lead's fid and the layer's new data version")
	void mergeReturnsTheContractShape() throws Exception {
		long lead = insert(SQUARE, "Führend");
		long other = insert(SQUARE_EAST, "Zweit");

		MockHttpServletResponse response = merge("""
				{"fids": [%d, %d], "leadFid": %d, "rowVersions": {"%d": "%s", "%d": "%s"}}
				""".formatted(lead, other, lead, lead, rowVersion(lead), other, rowVersion(other)));

		assertThat(response.getStatus()).isEqualTo(200);
		JsonNode body = bodyOf(response);
		assertThat(body.get("fid").asLong()).isEqualTo(lead);
		assertThat(body.get("dataVersion").asLong())
				.isEqualTo(layerRepository.findById(layer.getId()).orElseThrow().getDataVersion());
	}

	@Test
	@DisplayName("a lead outside the selection is a 400 carrying the contract's wording")
	void mergeWithAForeignLeadIsBadRequest() throws Exception {
		long first = insert(SQUARE, null);
		long second = insert(SQUARE_EAST, null);

		MockHttpServletResponse response = merge(
				"{\"fids\": [%d, %d], \"leadFid\": 999999}".formatted(first, second));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(bodyOf(response).get("detail").asString())
				.isEqualTo("Das führende Objekt gehört nicht zur Auswahl.");
	}

	@Test
	@DisplayName("one stale row version among the parts is a 409")
	void mergeWithAStaleRowVersionIsConflict() throws Exception {
		long lead = insert(SQUARE, null);
		long other = insert(SQUARE_EAST, null);

		MockHttpServletResponse response = merge("""
				{"fids": [%d, %d], "leadFid": %d, "rowVersions": {"%d": "1"}}
				""".formatted(lead, other, lead, other));

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(bodyOf(response).get("current").get("fid").asLong()).isEqualTo(other);
	}
}
