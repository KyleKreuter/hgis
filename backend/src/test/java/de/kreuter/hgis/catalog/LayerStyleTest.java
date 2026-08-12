package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.UUID;
import org.hamcrest.Matchers;
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

/**
 * Storing and validating a layer style, and the one rule the whole caching story rests
 * on: {@code style_version} moves when the tiles would have to carry different
 * attributes, and stays put for everything else.
 *
 * <p>That distinction is easy to break and invisible when broken -- a map that reloads
 * every tile on every drag of a colour picker still shows the right colours.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LayerStyleTest {

	private static final String SINGLE_RED = """
			{ "style": { "version": 1, "renderer": { "type": "single",
			  "symbol": { "kind": "fill", "fillColor": "#e74c3c", "fillOpacity": 0.5 } } } }
			""";

	private static final String SINGLE_GREEN = """
			{ "style": { "version": 1, "renderer": { "type": "single",
			  "symbol": { "kind": "fill", "fillColor": "#27ae60", "fillOpacity": 0.5 } } } }
			""";

	private static final String CATEGORIZED_BY_USE = """
			{ "style": { "renderer": { "type": "categorized", "field": "nutzungsart",
			  "categories": [ { "value": "Wohnen", "label": "Wohnbebauung",
			                    "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ],
			  "fallbackSymbol": { "kind": "fill", "fillColor": "#cccccc" } } } }
			""";

	/** Same classification field, different colour -- the case that must not move the version. */
	private static final String CATEGORIZED_BY_USE_RECOLOURED = """
			{ "style": { "renderer": { "type": "categorized", "field": "nutzungsart",
			  "categories": [ { "value": "Wohnen", "label": "Wohnbebauung",
			                    "symbol": { "kind": "fill", "fillColor": "#2980b9" } } ],
			  "fallbackSymbol": { "kind": "fill", "fillColor": "#333333" } } } }
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
	private LayerFieldRepository layerFieldRepository;

	@Autowired
	private LayerStyleService styleService;

	private Project project;
	private Layer layer;
	private String tableName;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Style-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    nutzungsart   text,
				    einwohner     integer,
				    gebaeudehoehe double precision,
				    geom          geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(SqlIdentifier.quoteLayerTable(tableName))).update();

		layer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Gebäude", tableName, "MULTIPOLYGON", 25832));

		layerFieldRepository.saveAndFlush(new LayerField(layer, "Nutzungsart", "nutzungsart", "text", 0));
		layerFieldRepository.saveAndFlush(new LayerField(layer, "Einwohner", "einwohner", "integer", 1));
		layerFieldRepository.saveAndFlush(
				new LayerField(layer, "Gebäudehöhe", "gebaeudehoehe", "double precision", 2));
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	// --- style_version ----------------------------------------------------------------

	@Test
	@DisplayName("a colour change leaves style_version alone")
	void recolouringDoesNotInvalidateTiles() throws Exception {
		patchStyle(SINGLE_RED).andExpect(status().isOk());
		long afterFirstStyle = styleVersion();

		patchStyle(SINGLE_GREEN)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.styleVersion").value((int) afterFirstStyle));

		assertThat(styleVersion()).isEqualTo(afterFirstStyle);
	}

	@Test
	@DisplayName("a colour change leaves style_version alone even with a classification")
	void recolouringACategorizedRendererDoesNotInvalidateTiles() throws Exception {
		patchStyle(CATEGORIZED_BY_USE).andExpect(status().isOk());
		long classified = styleVersion();

		patchStyle(CATEGORIZED_BY_USE_RECOLOURED).andExpect(status().isOk());

		assertThat(styleVersion())
				.as("dieselbe Klassifizierung in anderen Farben braucht keine neuen Kacheln")
				.isEqualTo(classified);
	}

	@Test
	@DisplayName("classifying by a field raises style_version, and so does changing that field")
	void changingTheAttributeSetInvalidatesTiles() throws Exception {
		long unstyled = styleVersion();

		// Asserted on the PATCH response, not only on the reloaded row: the client decides
		// from exactly this number whether to rebuild its tile URL or to recolour in place.
		patchStyle(CATEGORIZED_BY_USE)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.styleVersion").value((int) unstyled + 1));
		long classified = styleVersion();
		assertThat(classified).isGreaterThan(unstyled);

		patchStyle("""
				{ "style": { "renderer": { "type": "categorized", "field": "einwohner",
				  "categories": [ { "value": 1, "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.styleVersion").value((int) classified + 1));

		assertThat(styleVersion()).isGreaterThan(classified);
	}

	@Test
	@DisplayName("switching a classified renderer to a single symbol raises style_version again")
	void droppingTheClassificationInvalidatesTiles() throws Exception {
		patchStyle(CATEGORIZED_BY_USE).andExpect(status().isOk());
		long classified = styleVersion();

		patchStyle(SINGLE_RED).andExpect(status().isOk());

		assertThat(styleVersion()).isGreaterThan(classified);
	}

	@Test
	@DisplayName("switching labels on raises style_version, restyling them does not")
	void labelsCountTowardsTheAttributeSet() throws Exception {
		patchStyle(SINGLE_RED).andExpect(status().isOk());
		long withoutLabels = styleVersion();

		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#e74c3c" } },
				  "labels": { "enabled": true, "field": "nutzungsart", "size": 12,
				              "color": "#333333", "haloColor": "#ffffff", "haloWidth": 1.5,
				              "minZoom": 14, "allowOverlap": false } } }
				""").andExpect(status().isOk());
		long withLabels = styleVersion();
		assertThat(withLabels).isGreaterThan(withoutLabels);

		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#e74c3c" } },
				  "labels": { "enabled": true, "field": "nutzungsart", "size": 18,
				              "color": "#000000", "haloColor": "#ffffff", "haloWidth": 2,
				              "minZoom": 14, "allowOverlap": true } } }
				""").andExpect(status().isOk());

		assertThat(styleVersion())
				.as("groessere Schrift braucht keine neuen Kacheln")
				.isEqualTo(withLabels);
	}

	@Test
	@DisplayName("a labels block that is switched off pulls no attribute into the tiles")
	void disabledLabelsDoNotCountTowardsTheAttributeSet() throws Exception {
		patchStyle(SINGLE_RED).andExpect(status().isOk());
		long withoutLabels = styleVersion();

		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#e74c3c" } },
				  "labels": { "enabled": false, "field": "nutzungsart" } } }
				""").andExpect(status().isOk());

		assertThat(styleVersion()).isEqualTo(withoutLabels);
		assertThat(styleService.tileColumns(reload())).isEmpty();
	}

	@Test
	@DisplayName("clearing a classified style raises style_version, clearing a plain one does not")
	void clearingTheStyle() throws Exception {
		patchStyle(SINGLE_RED).andExpect(status().isOk());
		long plain = styleVersion();

		patchStyle("{ \"style\": null }")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style").doesNotExist());
		assertThat(styleVersion()).isEqualTo(plain);

		patchStyle(CATEGORIZED_BY_USE).andExpect(status().isOk());
		long classified = styleVersion();

		patchStyle("{ \"style\": null }").andExpect(status().isOk());
		assertThat(styleVersion()).isGreaterThan(classified);
	}

	@Test
	@DisplayName("an update that does not mention the style leaves it alone")
	void anUnrelatedUpdateKeepsTheStyle() throws Exception {
		patchStyle(CATEGORIZED_BY_USE).andExpect(status().isOk());
		long classified = styleVersion();

		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Gebäude, umbenannt\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.type").value("categorized"));

		assertThat(styleVersion()).isEqualTo(classified);
	}

	// --- what ends up stored ----------------------------------------------------------

	@Test
	@DisplayName("the layer list carries the style, so the map needs no request per layer")
	void styleIsPartOfTheLayerList() throws Exception {
		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].style").doesNotExist());

		patchStyle(CATEGORIZED_BY_USE).andExpect(status().isOk());

		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].style.renderer.type").value("categorized"))
				.andExpect(jsonPath("$[0].style.renderer.field").value("nutzungsart"))
				.andExpect(jsonPath("$[0].styleVersion").value(2));
	}

	@Test
	@DisplayName("the stored style comes back as an object, not as a string")
	void styleIsReturnedAsJson() throws Exception {
		patchStyle(CATEGORIZED_BY_USE).andExpect(status().isOk());

		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.version").value(1))
				.andExpect(jsonPath("$.style.renderer.type").value("categorized"))
				.andExpect(jsonPath("$.style.renderer.field").value("nutzungsart"))
				.andExpect(jsonPath("$.style.renderer.categories[0].value").value("Wohnen"))
				.andExpect(jsonPath("$.style.renderer.categories[0].symbol.fillColor").value("#e74c3c"));
	}

	@Test
	@DisplayName("a field named by its source name is stored as the column name the tile uses")
	void fieldNamesAreCanonicalised() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "graduated", "field": "Gebäudehöhe",
				  "classes": [ { "min": 0, "max": 10, "label": "0 – 10",
				                 "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.field").value("gebaeudehoehe"));

		assertThat(styleService.tileColumns(reload())).containsExactly("gebaeudehoehe");
	}

	@Test
	@DisplayName("a dash pattern survives storage; no pattern means the member is gone, not null")
	void dashArrayRoundTrips() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "line", "color": "#2980b9", "width": 2,
				              "dashArray": [2, 2] } } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.symbol.dashArray", Matchers.hasSize(2)))
				.andExpect(jsonPath("$.style.renderer.symbol.dashArray[0]").value(2.0));

		// Absent, not null: a solid line is the absence of a pattern. A client reading
		// symbol.dashArray gets undefined either way, one testing 'dashArray' in symbol
		// does not -- so this is worth pinning down rather than leaving to Jackson.
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "line", "color": "#2980b9", "width": 2,
				              "dashArray": null } } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.symbol.color").value("#2980b9"))
				.andExpect(jsonPath("$.style.renderer.symbol.dashArray").doesNotExist());
	}

	@Test
	@DisplayName("a category for the features without a value keeps its explicit null")
	void aNullCategoryValueSurvives() throws Exception {
		// The one member that is written even when null. Colouring "objects without a use
		// type" is a category like any other -- dropped from the document it would read as
		// an entry whose value was never picked, and nothing could tell the two apart.
		patchStyle("""
				{ "style": { "renderer": { "type": "categorized", "field": "nutzungsart",
				  "categories": [
				    { "value": "Wohnen", "symbol": { "kind": "fill", "fillColor": "#e74c3c" } },
				    { "value": null, "label": "Ohne Angabe",
				      "symbol": { "kind": "fill", "fillColor": "#999999" } },
				    { "label": "Wert nie gewählt" } ] } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.categories", Matchers.hasSize(3)))
				.andExpect(jsonPath("$.style.renderer.categories[1].label").value("Ohne Angabe"))
				.andExpect(jsonPath("$.style.renderer.categories[1]", Matchers.hasKey("value")))
				.andExpect(jsonPath("$.style.renderer.categories[1].value").doesNotExist())
				// A category that arrives without the member at all comes back carrying an
				// explicit null. So value is present on every category this API ever
				// returns, and a reader never has to tell "absent" from "null" -- only the
				// two spellings the client may send collapse into one.
				.andExpect(jsonPath("$.style.renderer.categories[2]", Matchers.hasKey("value")))
				.andExpect(jsonPath("$.style.renderer.categories[2].value").doesNotExist());
	}

	@Test
	@DisplayName("an empty category list stays an empty list, it does not vanish")
	void anEmptyCategoryListIsKept() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "categorized", "field": "nutzungsart",
				  "categories": [] } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.categories", Matchers.hasSize(0)));
	}

	@Test
	@DisplayName("a numeric category value stays a number, it does not become a string")
	void numericCategoryValuesKeepTheirType() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "categorized", "field": "einwohner",
				  "categories": [ { "value": 100, "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.categories[0].value").value(100))
				.andExpect(jsonPath("$.style.renderer.categories[0].value")
						.value(Matchers.instanceOf(Number.class)));
	}

	@Test
	@DisplayName("members the schema does not know are dropped rather than stored")
	void unknownMembersAreNotStored() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#e74c3c" } },
				  "somethingElse": { "nested": true } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.somethingElse").doesNotExist());
	}

	// --- the graduated/categorized metadata fields (method, classCount, ramp, palette) ---

	@Test
	@DisplayName("a graduated renderer's method, classCount and ramp survive storage and reading back")
	void graduatedMetadataFieldsRoundTrip() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "graduated", "field": "gebaeudehoehe",
				  "method": "equalInterval", "classCount": 5, "ramp": "viridis",
				  "classes": [ { "min": 0, "max": 10, "label": "0 – 10",
				                 "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.method").value("equalInterval"))
				.andExpect(jsonPath("$.style.renderer.classCount").value(5))
				.andExpect(jsonPath("$.style.renderer.ramp").value("viridis"));

		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.method").value("equalInterval"))
				.andExpect(jsonPath("$.style.renderer.classCount").value(5))
				.andExpect(jsonPath("$.style.renderer.ramp").value("viridis"));
	}

	@Test
	@DisplayName("a categorized renderer's palette survives storage and reading back")
	void categorizedPaletteRoundTrips() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "categorized", "field": "nutzungsart",
				  "palette": "categorical",
				  "categories": [ { "value": "Wohnen",
				                     "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.palette").value("categorical"));

		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.palette").value("categorical"));
	}

	@Test
	@DisplayName("a style without the new metadata fields still reads -- the existing case")
	void aStyleWithoutTheNewMetadataFieldsStillReads() throws Exception {
		patchStyle(CATEGORIZED_BY_USE).andExpect(status().isOk());

		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.renderer.type").value("categorized"))
				.andExpect(jsonPath("$.style.renderer.method").doesNotExist())
				.andExpect(jsonPath("$.style.renderer.classCount").doesNotExist())
				.andExpect(jsonPath("$.style.renderer.ramp").doesNotExist())
				.andExpect(jsonPath("$.style.renderer.palette").doesNotExist());
	}

	@Test
	void rejectsAnUnknownClassificationMethod() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "graduated", "field": "gebaeudehoehe",
				  "method": "jenks",
				  "classes": [ { "min": 0, "max": 10,
				                 "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAClassCountOutsideTwoToTwelve() throws Exception {
		patchStyle(graduatedStyleWithClassCount(1)).andExpect(status().isBadRequest());
		patchStyle(graduatedStyleWithClassCount(13)).andExpect(status().isBadRequest());
	}

	@Test
	void rejectsARampNameOverSixtyFourCharacters() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "graduated", "field": "gebaeudehoehe",
				  "ramp": "%s",
				  "classes": [ { "min": 0, "max": 10,
				                 "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
				""".formatted("x".repeat(65))).andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAPaletteNameOverSixtyFourCharacters() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "categorized", "field": "nutzungsart",
				  "palette": "%s",
				  "categories": [ { "value": "Wohnen",
				                     "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
				""".formatted("x".repeat(65))).andExpect(status().isBadRequest());
	}

	private static String graduatedStyleWithClassCount(int classCount) {
		return """
				{ "style": { "renderer": { "type": "graduated", "field": "gebaeudehoehe",
				  "classCount": %d,
				  "classes": [ { "min": 0, "max": 10,
				                 "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
				""".formatted(classCount);
	}

	// --- rejections -------------------------------------------------------------------

	@Test
	void rejectsAnUnknownRendererType() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "heatmap", "field": "einwohner" } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAFieldTheLayerDoesNotHave() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "categorized", "field": "geom; DROP TABLE" } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsALabelFieldTheLayerDoesNotHave() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#e74c3c" } },
				  "labels": { "enabled": true, "field": "existiert_nicht" } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAColourThatIsNotSixHexDigits() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "red" } } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnOpacityOutsideZeroToOne() throws Exception {
		patchStyle("""
				{ "style": { "opacity": 1.5, "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsANegativeWidth() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "line", "color": "#2980b9", "width": -2 } } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnUnknownSymbolKind() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "single",
				  "symbol": { "kind": "hatching", "fillColor": "#e74c3c" } } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("graduated needs a numeric field -- a text column has no ordering to step through")
	void rejectsAGraduatedRendererOverATextField() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "graduated", "field": "nutzungsart" } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsACategorizedRendererWithoutAField() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "categorized",
				  "categories": [ { "value": "Wohnen" } ] } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAStyleWithoutARenderer() throws Exception {
		patchStyle("{ \"style\": { \"opacity\": 0.5 } }").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnUnknownSchemaVersion() throws Exception {
		patchStyle("""
				{ "style": { "version": 99, "renderer": { "type": "single",
				  "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } } }
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsACategoryValueThatIsNotAScalar() throws Exception {
		patchStyle("""
				{ "style": { "renderer": { "type": "categorized", "field": "nutzungsart",
				  "categories": [ { "value": { "nested": true } } ] } } }
				""").andExpect(status().isBadRequest());
	}

	// --- helpers ----------------------------------------------------------------------

	private ResultActions patchStyle(String body) throws Exception {
		return mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private Layer reload() {
		return layerRepository.findById(layer.getId()).orElseThrow();
	}

	private long styleVersion() {
		return reload().getStyleVersion();
	}
}
