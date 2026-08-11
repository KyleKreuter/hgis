package de.kreuter.hgis.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A layer with more attributes than PostgreSQL allows arguments to a function.
 *
 * <p>The natural way to build a feature is one {@code json_build_object} with a key and a
 * value per attribute, and it is correct for as long as nobody imports a wide table:
 * {@code FUNC_MAX_ARGS} is 100, so at the fiftieth attribute the export stops with
 * "cannot pass more than 100 arguments to a function" and the layer simply cannot be
 * downloaded. Fifty-five attributes here, five past the edge, with the typed ones among
 * them -- because the ways around the limit that lose the types are the tempting ones.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeoJsonExportWideLayerTest {

	/** Five past the fifty at which the old formulation stopped working. */
	private static final int TEXT_FIELDS = 50;

	private static final ObjectMapper JSON = new ObjectMapper();

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
	private String tableName;

	/** column name, SQL type, source name -- in catalog order. */
	private record Field(String column, String type, String sourceName) {
	}

	private final List<Field> fields = fields();

	private static List<Field> fields() {
		List<Field> all = new ArrayList<>();
		IntStream.rangeClosed(1, TEXT_FIELDS)
				.forEach(i -> all.add(new Field("attribut_" + i, "text", "Attribut " + i)));
		all.add(new Field("einwohner", "bigint", "Einwohner"));
		all.add(new Field("hoehe", "double precision", "Höhe ü. NN"));
		all.add(new Field("denkmal", "boolean", "Denkmal?"));
		all.add(new Field("stichtag", "date", "Stichtag"));
		all.add(new Field("flaeche", "numeric(12,2)", "Fläche"));
		return List.copyOf(all);
	}

	@BeforeAll
	void createLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Breit-Test " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);

		StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(table)
				.append(" (fid bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY");
		for (Field field : fields) {
			ddl.append(", ").append(SqlIdentifier.quoteColumn(field.column()))
					.append(' ').append(field.type());
		}
		ddl.append(", geom geometry(MultiPoint, 25832) NOT NULL)");
		jdbc.sql(ddl.toString()).update();

		insertFullRow(table);
		insertEmptyRow(table);

		Layer newLayer =
				new Layer(layerId, project, "Breiter Layer", tableName, "MULTIPOINT", 25832);
		newLayer.setFeatureCount(2);
		layer = layerRepository.saveAndFlush(newLayer);

		for (int i = 0; i < fields.size(); i++) {
			Field field = fields.get(i);
			fieldRepository.saveAndFlush(new LayerField(layer, field.sourceName(), field.column(),
					field.type(), i));
		}
	}

	private void insertFullRow(String table) {
		StringBuilder columns = new StringBuilder();
		StringBuilder values = new StringBuilder();
		for (int i = 1; i <= TEXT_FIELDS; i++) {
			columns.append("attribut_").append(i).append(", ");
			values.append('\'').append("Wert ").append(i).append("', ");
		}
		jdbc.sql("INSERT INTO " + table + " (" + columns
				+ "einwohner, hoehe, denkmal, stichtag, flaeche, geom) VALUES (" + values
				+ "1200, 12.5, true, DATE '2024-03-01', 1234.50,"
				+ " ST_Multi(ST_SetSRID(ST_MakePoint(550000, 5930000), 25832)))").update();
	}

	/** Every attribute NULL: the other half of "the types survived". */
	private void insertEmptyRow(String table) {
		jdbc.sql("INSERT INTO " + table + " (geom)"
				+ " VALUES (ST_Multi(ST_SetSRID(ST_MakePoint(550100, 5930000), 25832)))")
				.update();
	}

	@AfterAll
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.deleteById(layer.getId());
		projectRepository.deleteById(project.getId());
	}

	@Test
	@DisplayName("a layer of 55 attributes exports completely")
	void exportsAWideLayer() throws Exception {
		assertThat(fields).hasSize(55);

		JsonNode collection = exportAsJson();

		assertThat(collection.get("type").asString()).isEqualTo("FeatureCollection");
		assertThat(collection.get("features")).hasSize(2);
		// The response has to be whole, not merely a 200: a query that fails halfway
		// through the stream leaves a truncated body behind a status that already said
		// everything was fine.
		assertThat(collection.get("features").get(1).get("geometry").get("type").asString())
				.isEqualTo("MultiPoint");
	}

	@Test
	@DisplayName("all 55 attributes are present, under their names and in catalog order")
	void keepsEveryAttributeInOrder() throws Exception {
		JsonNode properties = exportAsJson().get("features").get(0).get("properties");

		List<String> expected = new ArrayList<>();
		expected.add("fid");
		fields.forEach(field -> expected.add(field.sourceName()));

		assertThat(properties.propertyNames()).containsExactlyElementsOf(expected);
	}

	@Test
	@DisplayName("width costs nothing in type fidelity")
	void keepsTypesAtFullWidth() throws Exception {
		JsonNode features = exportAsJson().get("features");

		JsonNode filled = features.get(0).get("properties");
		assertThat(filled.get("Attribut 1").asString()).isEqualTo("Wert 1");
		assertThat(filled.get("Attribut " + TEXT_FIELDS).asString())
				.as("the last one, well past the old ceiling")
				.isEqualTo("Wert " + TEXT_FIELDS);
		assertThat(filled.get("Einwohner").isIntegralNumber()).isTrue();
		assertThat(filled.get("Einwohner").asLong()).isEqualTo(1200L);
		assertThat(filled.get("Höhe ü. NN").isFloatingPointNumber()).isTrue();
		assertThat(filled.get("Höhe ü. NN").asDouble()).isEqualTo(12.5);
		assertThat(filled.get("Denkmal?").isBoolean()).isTrue();
		assertThat(filled.get("Stichtag").asString()).isEqualTo("2024-03-01");
		assertThat(filled.get("Fläche").isNumber()).isTrue();
		assertThat(filled.get("Fläche").asDouble()).isEqualTo(1234.50);

		JsonNode empty = features.get(1).get("properties");
		assertThat(empty.get("fid").isIntegralNumber()).as("the row id is never null").isTrue();
		fields.forEach(field -> assertThat(empty.get(field.sourceName()).isNull())
				.as("%s is NULL and must stay null, not become an empty string",
						field.sourceName())
				.isTrue());
	}

	// --- helpers ---------------------------------------------------------------

	private JsonNode exportAsJson() throws Exception {
		MvcResult result = mockMvc
				.perform(get("/api/layers/" + layer.getId() + "/export.geojson"))
				.andReturn();
		if (result.getRequest().isAsyncStarted()) {
			result.getAsyncResult();
			mockMvc.perform(asyncDispatch(result));
		}
		MockHttpServletResponse response = result.getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}
}
