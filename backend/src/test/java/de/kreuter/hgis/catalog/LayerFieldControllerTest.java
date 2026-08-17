package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.EditService;
import de.kreuter.hgis.features.dto.EditDtos;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@code POST /api/layers/{layerId}/fields} and
 * {@code PATCH /api/layers/{layerId}/fields/{fieldId}} -- adding and renaming attribute
 * fields of an existing layer (CONTRACT.md phase 11), as opposed to {@link LayerCreateTest},
 * which covers fields supplied at layer creation.
 *
 * <p>The fixture layer already has one feature and one field ("Groesse" / {@code groesse})
 * so every test starts from a layer that behaves like an imported one, not an empty shell.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LayerFieldControllerTest {

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
	private Layer layer;
	private String tableName;
	private LayerField groesseField;
	private long existingFid;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Feld-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom    geometry(MultiPoint, 25832) NOT NULL,
				    groesse text
				)
				""".formatted(table)).update();
		existingFid = jdbc.sql("INSERT INTO " + table
						+ " (geom, groesse) VALUES (ST_Multi(ST_SetSRID(ST_MakePoint(0, 0), 25832)), 'gross')"
						+ " RETURNING fid")
				.query(Long.class)
				.single();

		Layer newLayer = new Layer(layerId, project, "Bäume", tableName, "MULTIPOINT", 25832);
		newLayer.setFeatureCount(1);
		layer = layerRepository.saveAndFlush(newLayer);

		groesseField = fieldRepository.saveAndFlush(new LayerField(layer, "Groesse", "groesse", "text", 0));
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	private String columnType(String columnName) {
		return jdbc.sql("""
				SELECT data_type FROM information_schema.columns
				WHERE table_schema = 'gis_data' AND table_name = :tableName AND column_name = :columnName
				""")
				.param("tableName", tableName)
				.param("columnName", columnName)
				.query(String.class)
				.single();
	}

	private boolean hasColumn(String columnName) {
		return jdbc.sql("""
				SELECT COUNT(*) > 0 FROM information_schema.columns
				WHERE table_schema = 'gis_data' AND table_name = :tableName AND column_name = :columnName
				""")
				.param("tableName", tableName)
				.param("columnName", columnName)
				.query(Boolean.class)
				.single();
	}

	// --- add field: happy paths ------------------------------------------------------

	@Test
	@DisplayName("adds a field, creating the column and a layer_field row with a following ordinal")
	void addsAFieldAndCreatesTheColumn() throws Exception {
		mockMvc.perform(post("/api/layers/{layerId}/fields", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Pflanzjahr\", \"type\": \"INTEGER\" }"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.sourceName").value("Pflanzjahr"))
				.andExpect(jsonPath("$.columnName").value("pflanzjahr"))
				.andExpect(jsonPath("$.dataType").value("integer"));

		assertThat(columnType("pflanzjahr")).isEqualTo("integer");

		List<LayerField> fields = fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId());
		assertThat(fields).hasSize(2);
		LayerField added = fields.get(1);
		assertThat(added.getSourceName()).isEqualTo("Pflanzjahr");
		assertThat(added.getColumnName()).isEqualTo("pflanzjahr");
		assertThat(added.getOrdinal()).as("ordinal follows the existing field, not zero").isEqualTo(1);

		Boolean existingRowIsNull = jdbc.sql(
						"SELECT pflanzjahr IS NULL FROM " + SqlIdentifier.quoteLayerTable(tableName)
								+ " WHERE fid = :fid")
				.param("fid", existingFid)
				.query(Boolean.class)
				.single();
		assertThat(existingRowIsNull).as("existing objects read NULL for a newly added field").isTrue();
	}

	@Test
	@DisplayName("the decisive proof: a field added to a layer with existing objects can be filled in and read back")
	void addedFieldCanBeFilledInThroughEditServiceApply() throws Exception {
		mockMvc.perform(post("/api/layers/{layerId}/fields", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Pflanzjahr\", \"type\": \"INTEGER\" }"))
				.andExpect(status().isCreated());

		EditDtos.Request request = new EditDtos.Request(
				List.of(), List.of(new EditDtos.Update(existingFid, null, null, Map.of("pflanzjahr", 1990))),
				List.of(), false);
		editService.apply(layer.getId(), request, null);

		Integer stored = jdbc.sql(
						"SELECT pflanzjahr FROM " + SqlIdentifier.quoteLayerTable(tableName) + " WHERE fid = :fid")
				.param("fid", existingFid)
				.query(Integer.class)
				.single();
		assertThat(stored).isEqualTo(1990);
	}

	// --- add field: trap 1 -------------------------------------------------------------

	@Test
	@DisplayName("trap 1: a field that normalises to an existing column is rejected, not silently suffixed")
	void rejectsAFieldThatNormalisesToAnExistingColumn() throws Exception {
		mockMvc.perform(post("/api/layers/{layerId}/fields", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Größe\", \"type\": \"TEXT\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").exists());

		assertThat(fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId())).hasSize(1);
		assertThat(hasColumn("groesse_1")).as("no numbered fallback column was created either").isFalse();
	}

	// --- add field: other errors --------------------------------------------------------

	@Test
	void returnsNotFoundForAnUnknownLayer() throws Exception {
		mockMvc.perform(post("/api/layers/{layerId}/fields", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Pflanzjahr\", \"type\": \"INTEGER\" }"))
				.andExpect(status().isNotFound());
	}

	@Test
	void rejectsABlankName() throws Exception {
		mockMvc.perform(post("/api/layers/{layerId}/fields", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"   \", \"type\": \"TEXT\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").exists());
	}

	@Test
	void rejectsAnUnknownFieldType() throws Exception {
		mockMvc.perform(post("/api/layers/{layerId}/fields", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Baujahr\", \"type\": \"STRING\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.type").exists());

		assertThat(hasColumn("baujahr")).isFalse();
	}

	@Test
	@DisplayName("the 50-field cap applies to adding as much as to creating")
	void rejectsAddingBeyondFiftyFields() throws Exception {
		for (int i = 1; i <= 49; i++) {
			mockMvc.perform(post("/api/layers/{layerId}/fields", layer.getId())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{ \"name\": \"Feld" + i + "\", \"type\": \"TEXT\" }"))
					.andExpect(status().isCreated());
		}
		assertThat(fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId())).hasSize(50);

		mockMvc.perform(post("/api/layers/{layerId}/fields", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Einer zu viel\", \"type\": \"TEXT\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").exists());

		assertThat(fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId())).hasSize(50);
	}

	// --- rename: happy paths -------------------------------------------------------------

	@Test
	@DisplayName("renames a field's display name, leaving column and type untouched")
	void renamesAField() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}/fields/{fieldId}", layer.getId(), groesseField.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Größe (geschätzt)\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceName").value("Größe (geschätzt)"))
				.andExpect(jsonPath("$.columnName").value("groesse"))
				.andExpect(jsonPath("$.dataType").value("text"));

		LayerField reloaded = fieldRepository.findById(groesseField.getId()).orElseThrow();
		assertThat(reloaded.getSourceName()).isEqualTo("Größe (geschätzt)");
		assertThat(reloaded.getColumnName()).isEqualTo("groesse");
		assertThat(reloaded.getDataType()).isEqualTo("text");
	}

	@Test
	@DisplayName("renaming a field to its own current name is a no-op, not a collision")
	void renamingToItsOwnNameIsAllowed() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}/fields/{fieldId}", layer.getId(), groesseField.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"groesse\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceName").value("groesse"));
	}

	@Test
	@DisplayName("a style pointing at the renamed field keeps working, and style_version does not move")
	void renameLeavesAStylePointingAtTheFieldWorking() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "style": { "version": 1, "renderer": { "type": "categorized", "field": "groesse",
								  "categories": [ { "value": "gross", "label": "Groß",
								                    "symbol": { "kind": "marker", "fillColor": "#e74c3c" } } ],
								  "fallbackSymbol": { "kind": "marker", "fillColor": "#cccccc" } } } }
								"""))
				.andExpect(status().isOk());

		long styleVersionBefore = layerRepository.findById(layer.getId()).orElseThrow().getStyleVersion();

		mockMvc.perform(patch("/api/layers/{layerId}/fields/{fieldId}", layer.getId(), groesseField.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Größe\" }"))
				.andExpect(status().isOk());

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getStyleVersion())
				.as("a pure label rename must not bump style_version")
				.isEqualTo(styleVersionBefore);
		assertThat(reloaded.getStyle()).contains("\"field\": \"groesse\"");
	}

	// --- rename: trap 2 ------------------------------------------------------------------

	@Test
	@DisplayName("trap 2: renaming onto another field's column_name is rejected")
	void rejectsRenamingOntoAnotherFieldsColumnName() throws Exception {
		LayerField other = fieldRepository.saveAndFlush(new LayerField(layer, "Baumart", "baumart", "text", 1));

		mockMvc.perform(patch("/api/layers/{layerId}/fields/{fieldId}", layer.getId(), groesseField.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"baumart\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").exists());

		assertThat(fieldRepository.findById(groesseField.getId()).orElseThrow().getSourceName())
				.isEqualTo("Groesse");
		assertThat(fieldRepository.findById(other.getId())).isPresent();
	}

	@Test
	@DisplayName("trap 2: renaming onto another field's source_name is rejected")
	void rejectsRenamingOntoAnotherFieldsSourceName() throws Exception {
		fieldRepository.saveAndFlush(new LayerField(layer, "Baumart", "baumart", "text", 1));

		mockMvc.perform(patch("/api/layers/{layerId}/fields/{fieldId}", layer.getId(), groesseField.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \" Baumart \" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name", containsString("Baumart")));
	}

	// --- rename: other errors ------------------------------------------------------------

	@Test
	void returnsNotFoundWhenTheLayerIsUnknown() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}/fields/{fieldId}", UUID.randomUUID(), groesseField.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Neu\" }"))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsNotFoundWhenTheFieldIsUnknown() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}/fields/{fieldId}", layer.getId(), UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Neu\" }"))
				.andExpect(status().isNotFound());
	}

	@Test
	void rejectsARenameToABlankName() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}/fields/{fieldId}", layer.getId(), groesseField.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"   \" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").exists());
	}
}
