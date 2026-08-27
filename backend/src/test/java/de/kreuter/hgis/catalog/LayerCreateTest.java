package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.FieldType;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.EditService;
import de.kreuter.hgis.features.dto.EditDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests for {@code POST /api/projects/{projectId}/layers} -- creating a brand-new,
 * empty layer (CONTRACT.md, phase 10), as opposed to {@link LayerControllerTest}, which
 * exercises a fixture layer that stands in for one produced by an import.
 *
 * <p>Separate from {@link LayerControllerTest} for the same reason as
 * {@link LayerReorderTest}: the annotations match exactly, so the whole suite still
 * shares one Testcontainers database, but every layer this class creates gets its own
 * physical table that has to be dropped again -- unlike the read/update/delete tests,
 * which reuse one shared fixture.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LayerCreateTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

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

	@Autowired
	private EditService editService;

	private Project project;

	/** Table names of every layer a test successfully created, dropped again in tearDown. */
	private final List<String> createdTables = new ArrayList<>();

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Layer-Anlage " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void tearDown() {
		for (String table : createdTables) {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(table)).update();
		}
		layerRepository.findByProjectOrdered(project.getId()).forEach(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	// --- request/response helpers -------------------------------------------------

	private static String field(String name, String type) {
		return "{ \"name\": \"" + name + "\", \"type\": \"" + type + "\" }";
	}

	private static String fieldsArray(String... fields) {
		return "[" + String.join(", ", fields) + "]";
	}

	/** @param fieldsJson the JSON array for "fields", or null to omit the member entirely */
	private static String createBody(String name, String geometryType, String fieldsJson) {
		return "{ \"name\": \"" + name + "\", \"geometryType\": \"" + geometryType + "\""
				+ (fieldsJson == null ? "" : ", \"fields\": " + fieldsJson) + " }";
	}

	/** Posts, expects 201, and registers the new table for cleanup before returning the body. */
	private JsonNode createLayer(String body) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn();

		JsonNode json = MAPPER.readTree(result.getResponse().getContentAsString());
		createdTables.add(SqlIdentifier.tableName(UUID.fromString(json.get("id").asText())));
		return json;
	}

	private String columnType(String tableName, String columnName) {
		return jdbc.sql("""
				SELECT data_type FROM information_schema.columns
				WHERE table_schema = 'gis_data' AND table_name = :tableName AND column_name = :columnName
				""")
				.param("tableName", tableName)
				.param("columnName", columnName)
				.query(String.class)
				.single();
	}

	// --- happy paths ----------------------------------------------------------------

	@Test
	@DisplayName("creates an empty layer with no attribute fields")
	void createsAnEmptyLayerWithoutFields() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("Baumkataster", "MULTIPOINT", null)))
				.andExpect(status().isCreated())
				.andReturn();

		JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
		UUID layerId = UUID.fromString(body.get("id").asText());
		createdTables.add(SqlIdentifier.tableName(layerId));

		assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/layers/" + layerId);
		assertThat(body.get("name").asText()).isEqualTo("Baumkataster");
		assertThat(body.get("geometryType").asText()).isEqualTo("MULTIPOINT");
		assertThat(body.get("featureCount").asLong()).isZero();
		assertThat(body.get("extent").isNull()).as("an empty layer has no extent yet").isTrue();
		assertThat(body.has("style")).as("no default style -- styleToMapLibre falls back on its own").isFalse();
		assertThat(body.get("visible").asBoolean()).isTrue();

		assertThat(fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId)).isEmpty();

		// Drawable, not just cataloged: fid and geom are the only columns.
		Boolean onlyFidAndGeom = jdbc.sql("""
				SELECT COUNT(*) = 2 FROM information_schema.columns
				WHERE table_schema = 'gis_data' AND table_name = :tableName
				""")
				.param("tableName", SqlIdentifier.tableName(layerId))
				.query(Boolean.class)
				.single();
		assertThat(onlyFidAndGeom).isTrue();
	}

	@Test
	@DisplayName("creates a layer with several attribute fields, keeping their order")
	void createsALayerWithMultipleFieldsInOrder() throws Exception {
		JsonNode created = createLayer(createBody("Baumkataster", "MULTIPOINT",
				fieldsArray(field("Art", "TEXT"), field("Pflanzjahr", "INTEGER"))));
		UUID layerId = UUID.fromString(created.get("id").asText());

		List<LayerField> fields = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId);
		assertThat(fields).hasSize(2);
		assertThat(fields.get(0).getSourceName()).isEqualTo("Art");
		assertThat(fields.get(0).getColumnName()).isEqualTo("art");
		assertThat(fields.get(0).getDataType()).isEqualTo("text");
		assertThat(fields.get(1).getSourceName()).isEqualTo("Pflanzjahr");
		assertThat(fields.get(1).getColumnName()).isEqualTo("pflanzjahr");
		assertThat(fields.get(1).getDataType()).isEqualTo("integer");
	}

	@Test
	@DisplayName("an umlaut field name is normalised for the column but keeps its display name")
	void normalisesAnUmlautFieldNameWhilePreservingSourceName() throws Exception {
		JsonNode created = createLayer(createBody("Gebäude", "MULTIPOLYGON",
				fieldsArray(field("Gebäudehöhe", "DOUBLE"))));
		UUID layerId = UUID.fromString(created.get("id").asText());

		LayerField field = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId).get(0);
		assertThat(field.getSourceName()).isEqualTo("Gebäudehöhe");
		assertThat(field.getColumnName()).isEqualTo("gebaeudehoehe");
	}

	@Test
	@DisplayName("layer names may repeat within a project")
	void allowsDuplicateLayerNamesWithinAProject() throws Exception {
		createLayer(createBody("Doppelt", "MULTIPOINT", null));
		JsonNode second = createLayer(createBody("Doppelt", "MULTIPOINT", null));

		assertThat(second.get("name").asText()).isEqualTo("Doppelt");
		assertThat(layerRepository.findByProjectOrdered(project.getId())).hasSize(2);
	}

	@Test
	@DisplayName("exactly 50 fields is the allowed maximum")
	void acceptsExactlyFiftyFields() throws Exception {
		String[] entries = new String[50];
		for (int i = 0; i < entries.length; i++) {
			entries[i] = field("Feld" + i, "TEXT");
		}

		JsonNode created = createLayer(createBody("Viele Felder", "MULTIPOINT", fieldsArray(entries)));
		UUID layerId = UUID.fromString(created.get("id").asText());
		assertThat(fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId)).hasSize(50);
	}

	@ParameterizedTest
	@ValueSource(strings = { "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRY" })
	@DisplayName("each allowed geometry type produces a matching PostGIS geometry column")
	void createsTheRequestedGeometryType(String geometryType) throws Exception {
		JsonNode created = createLayer(createBody("Geometrietest " + geometryType, geometryType, null));
		UUID layerId = UUID.fromString(created.get("id").asText());

		String pgGeometryType = jdbc.sql("""
				SELECT type FROM geometry_columns
				WHERE f_table_schema = 'gis_data' AND f_table_name = :tableName AND f_geometry_column = 'geom'
				""")
				.param("tableName", SqlIdentifier.tableName(layerId))
				.query(String.class)
				.single();

		assertThat(pgGeometryType).isEqualTo(geometryType);
		assertThat(created.get("geometryType").asText()).isEqualTo(geometryType);
	}

	static Stream<Arguments> fieldTypes() {
		// Expected values as information_schema.columns.data_type normalises them --
		// PostgreSQL's own display form, not necessarily the DDL token FieldType writes.
		return Stream.of(
				Arguments.of(FieldType.TEXT, "text"),
				Arguments.of(FieldType.INTEGER, "integer"),
				Arguments.of(FieldType.BIGINT, "bigint"),
				Arguments.of(FieldType.DOUBLE, "double precision"),
				Arguments.of(FieldType.NUMERIC, "numeric"),
				Arguments.of(FieldType.BOOLEAN, "boolean"),
				Arguments.of(FieldType.DATE, "date"),
				Arguments.of(FieldType.TIME, "time without time zone"),
				Arguments.of(FieldType.TIMESTAMP, "timestamp with time zone"));
	}

	@ParameterizedTest
	@MethodSource("fieldTypes")
	@DisplayName("every FieldType lands as the correct PostgreSQL column type")
	void createsEachFieldTypeAsTheCorrectColumn(FieldType type, String expectedColumnType) throws Exception {
		JsonNode created = createLayer(createBody("Typtest " + type, "MULTIPOINT",
				fieldsArray(field("Wert", type.name()))));
		UUID layerId = UUID.fromString(created.get("id").asText());

		assertThat(columnType(SqlIdentifier.tableName(layerId), "wert")).isEqualTo(expectedColumnType);

		LayerField field = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId).get(0);
		assertThat(field.getDataType()).isEqualTo(type.pgType());
		assertThat(field.getSourceName()).isEqualTo("Wert");
	}

	// --- the point of the endpoint: drawing into what it just created ---------------

	@Test
	@DisplayName("a freshly created layer can immediately be drawn into, and feature_count follows")
	void aFreshlyCreatedLayerCanBeDrawnInto() throws Exception {
		JsonNode created = createLayer(createBody("Baumkataster", "MULTIPOINT",
				fieldsArray(field("Art", "TEXT"))));
		UUID layerId = UUID.fromString(created.get("id").asText());

		JsonNode geometry = MAPPER.readTree("""
				{"type":"Point","coordinates":[9.98,53.54]}
				""");
		EditDtos.Request request = new EditDtos.Request(
				List.of(new EditDtos.Create(-1, geometry, Map.of("art", "Eiche"))), null, null, false);

		EditDtos.Response response = editService.apply(layerId, request, null);

		assertThat(response.createdFids()).containsOnlyKeys(-1L);
		assertThat(response.featureCount()).isEqualTo(1);

		Layer reloaded = layerRepository.findById(layerId).orElseThrow();
		assertThat(reloaded.getFeatureCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("a GEOMETRY layer takes a point, a line and a polygon side by side")
	void aGeometryLayerAcceptsAPointALineAndAPolygonTogether() throws Exception {
		JsonNode created = createLayer(createBody("Gemischt", "GEOMETRY", null));
		UUID layerId = UUID.fromString(created.get("id").asText());

		JsonNode point = MAPPER.readTree("""
				{"type":"Point","coordinates":[9.98,53.54]}
				""");
		JsonNode line = MAPPER.readTree("""
				{"type":"LineString","coordinates":[[9.98,53.54],[9.99,53.55]]}
				""");
		JsonNode polygon = MAPPER.readTree("""
				{"type":"Polygon","coordinates":[[[9.98,53.54],[9.99,53.54],[9.99,53.55],[9.98,53.54]]]}
				""");

		EditDtos.Request request = new EditDtos.Request(
				List.of(new EditDtos.Create(-1, point, Map.of()),
						new EditDtos.Create(-2, line, Map.of()),
						new EditDtos.Create(-3, polygon, Map.of())),
				null, null, false);

		EditDtos.Response response = editService.apply(layerId, request, null);

		assertThat(response.createdFids()).containsOnlyKeys(-1L, -2L, -3L);
		assertThat(response.featureCount()).isEqualTo(3);

		List<String> storedTypes = jdbc.sql("SELECT GeometryType(geom) AS type FROM "
						+ SqlIdentifier.quoteLayerTable(SqlIdentifier.tableName(layerId))
						+ " ORDER BY fid")
				.query(String.class)
				.list();
		assertThat(storedTypes).containsExactly("MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON");
	}

	// --- errors -----------------------------------------------------------------

	@Test
	void returnsNotFoundForAnUnknownProject() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/layers", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("Layer", "MULTIPOINT", null)))
				.andExpect(status().isNotFound());
	}

	@Test
	void rejectsABlankName() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("   ", "MULTIPOINT", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").exists());
	}

	@Test
	void rejectsANameLongerThan200Characters() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("A".repeat(201), "MULTIPOINT", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").exists());
	}

	@Test
	void rejectsAMissingGeometryType() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Ohne Typ\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.geometryType").exists());
	}

	/**
	 * {@code POINT} rather than an arbitrary bad token: it is the single-geometry guess
	 * almost everyone makes first, and Aufgabe 18 requires the rejection to name the multi
	 * variant to use instead, not just that "POINT" was unknown.
	 */
	@Test
	void rejectsAnUnknownGeometryTypeToken() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("Layer", "POINT", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.geometryType").value(
						"Unbekannter Geometrietyp: POINT. Gültig sind MULTIPOINT, MULTILINESTRING, "
								+ "MULTIPOLYGON, GEOMETRY -- für Punkte nehmen Sie MULTIPOINT."));
	}

	@Test
	void rejectsAnUnknownFieldType() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("Layer", "MULTIPOINT", fieldsArray(field("Art", "STRING")))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.fields").value(
						"Unbekannter Feldtyp: STRING. Gültig sind TEXT, INTEGER, BIGINT, DOUBLE, "
								+ "NUMERIC, BOOLEAN, DATE, TIME, TIMESTAMP."));
	}

	static Stream<Arguments> fieldTypeTokensAsDescribeLayerReportsThem() {
		// Exactly the layer_field.data_type value describe_layer hands back for an
		// existing field of this type -- see LayerField#getDataType and FieldType#pgType.
		return Stream.of(
				Arguments.of("text", FieldType.TEXT),
				Arguments.of("bigint", FieldType.BIGINT),
				Arguments.of("double precision", FieldType.DOUBLE),
				Arguments.of("timestamptz", FieldType.TIMESTAMP));
	}

	/**
	 * Befund 2 (Validierung, 27.08.): {@code describe_layer} reports a field's type
	 * lower-case, in {@code layer_field.data_type}'s own spelling ({@code "double
	 * precision"}, {@code "timestamptz"}, ...); {@code create_layer} used to demand the
	 * upper-case constant name and nothing else. A caller reading a type off one field to
	 * create another with the same type -- the exact round trip the MCP tools invite --
	 * ran straight into that mismatch.
	 */
	@ParameterizedTest
	@MethodSource("fieldTypeTokensAsDescribeLayerReportsThem")
	@DisplayName("accepts a field type exactly as describe_layer would report it back")
	void acceptsAFieldTypeSpelledAsDescribeLayerReportsIt(String token, FieldType expected) throws Exception {
		JsonNode created = createLayer(createBody("Rundreise " + token, "MULTIPOINT",
				fieldsArray(field("Wert", token))));
		UUID layerId = UUID.fromString(created.get("id").asText());

		LayerField field = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId).get(0);
		assertThat(field.getDataType()).isEqualTo(expected.pgType());
	}

	@Test
	void rejectsABlankFieldName() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("Layer", "MULTIPOINT", fieldsArray(field("   ", "TEXT")))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.fields").exists());
	}

	@Test
	@DisplayName("two fields with the same display name are rejected, case-insensitively after trim")
	void rejectsDuplicateFieldNames() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("Layer", "MULTIPOINT",
								fieldsArray(field("Art", "TEXT"), field(" art ", "INTEGER")))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.fields", containsString("art")));

		assertThat(layerRepository.findByProjectOrdered(project.getId())).isEmpty();
	}

	@Test
	void rejectsMoreThanFiftyFields() throws Exception {
		String[] entries = new String[51];
		for (int i = 0; i < entries.length; i++) {
			entries[i] = field("Feld" + i, "TEXT");
		}

		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("Zu viele Felder", "MULTIPOINT", fieldsArray(entries))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.fields").exists());

		assertThat(layerRepository.findByProjectOrdered(project.getId())).isEmpty();
	}
}
