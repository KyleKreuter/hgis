package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.tiles.LayerTableFixture.TestLayer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP-level tests for {@link TileController}: status codes, headers, and the
 * conditional-request path. Feature content itself (does the tile actually contain
 * the right geometries) is covered at the {@link MvtService} level by
 * {@link MvtServiceTest}; this class only needs one small tile of data.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TileControllerTest {

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
	private MvtService mvtService;

	private TestLayer testLayer;
	private Layer layer;

	@BeforeEach
	void setUp() {
		testLayer = LayerTableFixture.create(jdbc, 3);

		Project project = projectRepository.saveAndFlush(
				new Project("Kachel-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		Layer newLayer = new Layer(UUID.randomUUID(), project, "Testlayer",
				testLayer.tableName(), "MULTIPOLYGON", 25832);
		layer = layerRepository.saveAndFlush(newLayer);

		layerFieldRepository.saveAndFlush(new LayerField(layer, "Nutzungsart",
				LayerTableFixture.CATEGORY_COLUMN, "text", 0));
		layerFieldRepository.saveAndFlush(new LayerField(layer, "Einwohner",
				LayerTableFixture.NUMERIC_COLUMN, "integer", 1));
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(testLayer.tableName())).update();
		layerRepository.delete(layer);
		projectRepository.delete(layer.getProject());
	}

	@Test
	void returnsMvtBodyForAPopulatedTile() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt",
						layer.getId(), testLayer.zoom(), testLayer.tileX(), testLayer.tileY()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/vnd.mapbox-vector-tile"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable"))
				.andExpect(header().exists(HttpHeaders.ETAG))
				.andReturn();

		byte[] body = result.getResponse().getContentAsByteArray();
		var layers = MvtTileDecoder.decode(body);
		assertThat(layers).hasSize(1);
		assertThat(layers.get(0).featureIds())
				.containsExactlyInAnyOrderElementsOf(testLayer.featureIds());
	}

	@Test
	@DisplayName("a styled layer serves its classification attribute in the tile")
	void carriesTheAttributeTheStyleClassifiesBy() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "style": { "renderer": { "type": "categorized", "field": "Nutzungsart",
								  "categories": [ { "value": "Wohnen",
								                    "symbol": { "kind": "fill", "fillColor": "#e74c3c" } } ] } } }
								"""))
				.andExpect(status().isOk());

		MvcResult result = mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt",
						layer.getId(), testLayer.zoom(), testLayer.tileX(), testLayer.tileY()))
				.andExpect(status().isOk())
				.andReturn();

		var decoded = MvtTileDecoder.decode(result.getResponse().getContentAsByteArray()).get(0);
		assertThat(decoded.keys()).containsExactly(LayerTableFixture.CATEGORY_COLUMN);
		assertThat(decoded.features())
				.allSatisfy(feature -> assertThat(feature.properties())
						.containsEntry(LayerTableFixture.CATEGORY_COLUMN,
								testLayer.categories().get(feature.id())));
	}

	/**
	 * The values offered for a categorized renderer have to be comparable to what the tile
	 * carries, because that comparison is the whole mechanism: MapLibre's {@code match}
	 * checks the type as well as the value, so a category picked as text against a numeric
	 * tile property matches nothing and every feature falls to the fallback colour. Nothing
	 * about that looks broken -- the map just turns uniformly grey.
	 *
	 * <p>The two sides are produced by entirely separate code (JDBC via {@code /values},
	 * ST_AsMVT in the tile), so their agreement is an assumption worth holding on to.
	 */
	@Test
	@DisplayName("a value from /values has the type the same attribute has in the tile")
	void offeredValuesMatchTheTypeInTheTile() throws Exception {
		byte[] mvt = mvtService.renderTile(testLayer.tableName(), 25832,
				List.of(LayerTableFixture.CATEGORY_COLUMN, LayerTableFixture.NUMERIC_COLUMN), null, null,
				testLayer.zoom(), testLayer.tileX(), testLayer.tileY());
		Map<String, Object> inTile = MvtTileDecoder.decode(mvt).get(0).features().get(0).properties();

		assertThat(firstValue(LayerTableFixture.CATEGORY_COLUMN).isString())
				.as("Textspalte: /values liefert einen String, die Kachel auch")
				.isTrue();
		assertThat(inTile.get(LayerTableFixture.CATEGORY_COLUMN)).isInstanceOf(String.class);

		assertThat(firstValue(LayerTableFixture.NUMERIC_COLUMN).isIntegralNumber())
				.as("Ganzzahlspalte: /values liefert eine ganze Zahl, die Kachel auch")
				.isTrue();
		assertThat(inTile.get(LayerTableFixture.NUMERIC_COLUMN)).isInstanceOf(Long.class);
	}

	/** The most frequent non-null value {@code /values} offers for a column. */
	private JsonNode firstValue(String field) throws Exception {
		String json = mockMvc.perform(get("/api/layers/{layerId}/values", layer.getId())
						.param("field", field))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		for (JsonNode entry : new ObjectMapper().readTree(json).get("values")) {
			if (!entry.get("value").isNull()) {
				return entry.get("value");
			}
		}
		throw new AssertionError("keine nicht-leeren Werte fuer " + field);
	}

	@Test
	void returnsNoContentForATileOutsideTheData() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", layer.getId(), 0, 0, 0))
				.andExpect(status().isNoContent())
				.andExpect(header().exists(HttpHeaders.ETAG));
	}

	@Test
	void rejectsAZoomLevelAboveTwentyFour() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", layer.getId(), 25, 0, 0))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnXOutsideTheRangeForItsZoomLevel() throws Exception {
		// z=1 only has tiles 0 and 1 on each axis.
		mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", layer.getId(), 1, 5, 0))
				.andExpect(status().isBadRequest());
	}

	@Test
	void returnsNotFoundForAnUnknownLayer() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", UUID.randomUUID(), 10, 0, 0))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsNotModifiedWhenTheEtagMatches() throws Exception {
		MvcResult first = mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt",
						layer.getId(), testLayer.zoom(), testLayer.tileX(), testLayer.tileY()))
				.andExpect(status().isOk())
				.andReturn();

		String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
		assertThat(etag).isNotNull();

		mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt",
						layer.getId(), testLayer.zoom(), testLayer.tileX(), testLayer.tileY())
						.header(HttpHeaders.IF_NONE_MATCH, etag))
				.andExpect(status().isNotModified());
	}
}
