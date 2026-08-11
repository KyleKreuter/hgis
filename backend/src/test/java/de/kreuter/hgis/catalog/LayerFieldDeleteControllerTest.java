package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests for {@code GET /api/layers/{layerId}/fields/{fieldId}/usage} and
 * {@code DELETE /api/layers/{layerId}/fields/{fieldId}} (CONTRACT.md phase 12), as
 * opposed to {@link LayerFieldControllerTest}, which covers adding and renaming.
 *
 * <p>The decisive test is {@link #stylePointingAtADeletedFieldCanBeSavedAgain()}: without
 * the cleanup CONTRACT.md calls "die Sackgasse", the PATCH in there is exactly the one
 * that would fail forever after -- {@code applyStyle} re-validates the stored style
 * against the layer's fields on every save, and a style still naming a dropped column
 * would be rejected with a 400 no client could ever fix.
 *
 * <p>The fixture layer carries three fields and three objects with a deliberate mix of
 * values and {@code NULL}s, so {@code usage}'s {@code valueCount} has something real to
 * count and "the other fields survive" has something real to check.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LayerFieldDeleteControllerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String CATEGORIZED_ON_NUTZUNGSART = """
			{ "style": { "version": 1, "renderer": { "type": "categorized", "field": "nutzungsart",
			  "categories": [ { "value": "Wohnen", "label": "Wohnbebauung",
			                    "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ],
			  "fallbackSymbol": { "kind": "fill", "fillColor": "#cccccc" } } } }
			""";

	private static final String SQUARE = """
			{"type":"Polygon","coordinates":[[[9.98,53.54],[9.99,53.54],[9.99,53.55],[9.98,53.55],[9.98,53.54]]]}
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

	@Autowired
	private EditService editService;

	private Project project;
	private Layer layer;
	private String tableName;
	private LayerField nutzungsartField;
	private LayerField einwohnerField;
	private LayerField gebaeudehoeheField;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Feld-Loeschen-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom          geometry(MultiPolygon, 25832) NOT NULL,
				    nutzungsart   text,
				    einwohner     integer,
				    gebaeudehoehe double precision
				)
				""".formatted(table)).update();

		// Three objects, deliberately holed: NULL competes with a real value in every
		// column at least once, so a valueCount that forgot to exclude NULL would show up.
		jdbc.sql("""
				INSERT INTO %s (geom, nutzungsart, einwohner, gebaeudehoehe) VALUES
				    (ST_Multi(ST_MakeEnvelope(0, 0, 10, 10, 25832)), 'Wohnen', 100, 12.5),
				    (ST_Multi(ST_MakeEnvelope(20, 0, 30, 10, 25832)), 'Gewerbe', NULL, NULL),
				    (ST_Multi(ST_MakeEnvelope(40, 0, 50, 10, 25832)), NULL, NULL, NULL)
				""".formatted(table)).update();

		Layer newLayer = new Layer(layerId, project, "Flurstücke", tableName, "MULTIPOLYGON", 25832);
		newLayer.setFeatureCount(3);
		layer = layerRepository.saveAndFlush(newLayer);

		nutzungsartField = fieldRepository.saveAndFlush(new LayerField(layer, "Nutzungsart", "nutzungsart", "text", 0));
		einwohnerField = fieldRepository.saveAndFlush(new LayerField(layer, "Einwohner", "einwohner", "integer", 1));
		gebaeudehoeheField = fieldRepository.saveAndFlush(
				new LayerField(layer, "Gebäudehöhe", "gebaeudehoehe", "double precision", 2));
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
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

	private ResultActions patchStyle(String body) throws Exception {
		return mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions deleteField(UUID fieldId) throws Exception {
		return mockMvc.perform(delete("/api/layers/{layerId}/fields/{fieldId}", layer.getId(), fieldId));
	}

	private Layer reload() {
		return layerRepository.findById(layer.getId()).orElseThrow();
	}

	private long styleVersion() {
		return reload().getStyleVersion();
	}

	private long dataVersion() {
		return reload().getDataVersion();
	}

	// --- usage --------------------------------------------------------------------------

	@Test
	@DisplayName("usage reports the number of objects with a value, NULL excluded, on an unstyled layer")
	void usageReportsValueCountExcludingNull() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/fields/{fieldId}/usage", layer.getId(), nutzungsartField.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.valueCount").value(2))
				.andExpect(jsonPath("$.usedByRenderer").value(false))
				.andExpect(jsonPath("$.usedByLabels").value(false));

		mockMvc.perform(get("/api/layers/{layerId}/fields/{fieldId}/usage", layer.getId(), einwohnerField.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.valueCount").value(1));
	}

	@Test
	@DisplayName("usage flags exactly the field the renderer classifies by")
	void usageFlagsTheRendererField() throws Exception {
		patchStyle(CATEGORIZED_ON_NUTZUNGSART).andExpect(status().isOk());

		mockMvc.perform(get("/api/layers/{layerId}/fields/{fieldId}/usage", layer.getId(), nutzungsartField.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.usedByRenderer").value(true))
				.andExpect(jsonPath("$.usedByLabels").value(false));

		mockMvc.perform(get("/api/layers/{layerId}/fields/{fieldId}/usage", layer.getId(), einwohnerField.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.usedByRenderer").value(false));
	}

	@Test
	@DisplayName("usage flags a field enabled labels read")
	void usageFlagsTheLabelsField() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#404040" } },
				  "labels": { "enabled": true, "field": "gebaeudehoehe", "size": 12,
				              "color": "#333333", "haloColor": "#ffffff", "haloWidth": 1.5,
				              "minZoom": 14, "allowOverlap": false } } }
				""").andExpect(status().isOk());

		mockMvc.perform(get("/api/layers/{layerId}/fields/{fieldId}/usage", layer.getId(), gebaeudehoeheField.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.usedByRenderer").value(false))
				.andExpect(jsonPath("$.usedByLabels").value(true));
	}

	@Test
	void usageReturnsNotFoundForAnUnknownLayer() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/fields/{fieldId}/usage", UUID.randomUUID(), nutzungsartField.getId()))
				.andExpect(status().isNotFound());
	}

	@Test
	void usageReturnsNotFoundForAnUnknownField() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/fields/{fieldId}/usage", layer.getId(), UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	// --- delete: happy path ---------------------------------------------------------------

	@Test
	@DisplayName("deletes the column and the catalog row, leaving the other fields and their values untouched")
	void deletesTheColumnAndCatalogRow() throws Exception {
		deleteField(einwohnerField.getId()).andExpect(status().isNoContent());

		assertThat(hasColumn("einwohner")).isFalse();
		assertThat(fieldRepository.findById(einwohnerField.getId())).isEmpty();

		List<LayerField> remaining = fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId());
		assertThat(remaining).extracting(LayerField::getColumnName)
				.containsExactly("nutzungsart", "gebaeudehoehe");
		assertThat(hasColumn("nutzungsart")).isTrue();
		assertThat(hasColumn("gebaeudehoehe")).isTrue();

		List<String> survivingValues = jdbc.sql(
						"SELECT nutzungsart FROM " + SqlIdentifier.quoteLayerTable(tableName) + " ORDER BY fid")
				.query(String.class)
				.list();
		assertThat(survivingValues).containsExactly("Wohnen", "Gewerbe", null);
	}

	@Test
	void deleteReturnsNotFoundForAnUnknownLayer() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}/fields/{fieldId}", UUID.randomUUID(), nutzungsartField.getId()))
				.andExpect(status().isNotFound());
		assertThat(fieldRepository.findById(nutzungsartField.getId())).isPresent();
	}

	@Test
	void deleteReturnsNotFoundForAnUnknownField() throws Exception {
		deleteField(UUID.randomUUID()).andExpect(status().isNotFound());
		assertThat(fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId())).hasSize(3);
	}

	// --- delete: the decisive proof ---------------------------------------------------------

	@Test
	@DisplayName("the decisive proof: after deleting a classified field, the renderer falls back to " +
			"single and the style can be saved again")
	void stylePointingAtADeletedFieldCanBeSavedAgain() throws Exception {
		patchStyle(CATEGORIZED_ON_NUTZUNGSART).andExpect(status().isOk());

		deleteField(nutzungsartField.getId()).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.type").value("single"))
				.andExpect(jsonPath("$.style.renderer.field").doesNotExist())
				.andExpect(jsonPath("$.style.renderer.categories").doesNotExist())
				// the categorized renderer's fallbackSymbol survives as the single symbol
				.andExpect(jsonPath("$.style.renderer.symbol.fillColor").value("#cccccc"));

		// Without the cleanup, this is exactly the save that used to fail forever: the
		// stored style still names "nutzungsart", applyStyle re-validates it against the
		// layer's current fields on every write, and LayerFields.require throws a 400.
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#2980b9" } } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.type").value("single"))
				.andExpect(jsonPath("$.style.renderer.symbol.fillColor").value("#2980b9"));
	}

	@Test
	@DisplayName("deleting a field the labels read switches the labels off, and saving still works")
	void deletingALabelledFieldDisablesLabels() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#404040" } },
				  "labels": { "enabled": true, "field": "gebaeudehoehe", "size": 12,
				              "color": "#333333", "haloColor": "#ffffff", "haloWidth": 1.5,
				              "minZoom": 14, "allowOverlap": false } } }
				""").andExpect(status().isOk());

		deleteField(gebaeudehoeheField.getId()).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.labels.enabled").value(false))
				.andExpect(jsonPath("$.style.labels.field").doesNotExist())
				.andExpect(jsonPath("$.style.renderer.type").value("single"));

		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#404040" } } } }
				""").andExpect(status().isOk());
	}

	// --- delete: style_version and data_version --------------------------------------------

	@Test
	@DisplayName("style_version moves only when the deleted field was actually styled; data_version never moves")
	void styleVersionMovesOnlyWhenTheDeletedFieldWasStyled() throws Exception {
		patchStyle(CATEGORIZED_ON_NUTZUNGSART).andExpect(status().isOk());
		long classified = styleVersion();
		long dataBefore = dataVersion();

		// gebaeudehoehe never appears in the style -- deleting it must not bump style_version.
		deleteField(gebaeudehoeheField.getId()).andExpect(status().isNoContent());
		assertThat(styleVersion()).isEqualTo(classified);
		assertThat(dataVersion()).isEqualTo(dataBefore);

		// nutzungsart is the classification itself -- deleting it must bump style_version.
		deleteField(nutzungsartField.getId()).andExpect(status().isNoContent());
		assertThat(styleVersion()).isGreaterThan(classified);
		assertThat(dataVersion()).as("dropping a column never touches data_version").isEqualTo(dataBefore);
	}

	// --- delete: ordinal gaps and last field ------------------------------------------------

	@Test
	@DisplayName("a field deleted from the middle leaves the layer consistent for the next field added")
	void deletingAMiddleFieldThenAddingANewOneStaysConsistent() throws Exception {
		deleteField(einwohnerField.getId()).andExpect(status().isNoContent());

		mockMvc.perform(post("/api/layers/{layerId}/fields", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Baujahr\", \"type\": \"INTEGER\" }"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.columnName").value("baujahr"));

		assertThat(hasColumn("baujahr")).isTrue();
		assertThat(fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId()))
				.extracting(LayerField::getColumnName)
				.containsExactly("nutzungsart", "gebaeudehoehe", "baujahr");
	}

	@Test
	@DisplayName("deleting a layer's last field is allowed, and the layer can still be drawn into")
	void deletingTheLastFieldStillAllowsDrawing() throws Exception {
		deleteField(nutzungsartField.getId()).andExpect(status().isNoContent());
		deleteField(einwohnerField.getId()).andExpect(status().isNoContent());
		deleteField(gebaeudehoeheField.getId()).andExpect(status().isNoContent());

		assertThat(fieldRepository.findByLayerIdOrderByOrdinalAsc(layer.getId())).isEmpty();
		assertThat(hasColumn("nutzungsart")).isFalse();
		assertThat(hasColumn("einwohner")).isFalse();
		assertThat(hasColumn("gebaeudehoehe")).isFalse();

		EditDtos.Request request = new EditDtos.Request(
				List.of(new EditDtos.Create(-1, json(SQUARE), Map.of())), null, null, false);
		EditDtos.Response response = editService.apply(layer.getId(), request);

		assertThat(response.createdFids()).hasSize(1);
	}

	private static JsonNode json(String geoJson) {
		return MAPPER.readTree(geoJson);
	}
}
