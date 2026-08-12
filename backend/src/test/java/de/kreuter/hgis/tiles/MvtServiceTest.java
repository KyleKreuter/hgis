package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.tiles.LayerTableFixture.TestLayer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises MvtService directly against a real PostGIS database: renders a tile with
 * known features, an empty one, a styled one carrying its classification attribute, and
 * proves the query plan stays index-friendly either way.
 *
 * Table setup runs once for the whole class ({@link TestInstance.Lifecycle#PER_CLASS})
 * because the noise data (5000 rows) that makes the EXPLAIN proof meaningful is not
 * cheap to regenerate per test method.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MvtServiceTest {

	@Autowired
	private MvtService mvtService;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestLayer testLayer;

	@BeforeAll
	void setUp() {
		testLayer = LayerTableFixture.create(jdbc, 4);
	}

	@Test
	@DisplayName("renders a tile containing exactly the known signal features")
	void rendersExpectedFeatures() {
		byte[] mvt = mvtService.renderTile(testLayer.tableName(), 25832, List.of(), List.of(),
				testLayer.zoom(), testLayer.tileX(), testLayer.tileY());

		assertThat(mvt).isNotNull();

		List<MvtTileDecoder.Layer> layers = MvtTileDecoder.decode(mvt);
		assertThat(layers).hasSize(1);
		assertThat(layers.get(0).name()).isEqualTo("layer");
		assertThat(layers.get(0).featureIds())
				.containsExactlyInAnyOrderElementsOf(testLayer.featureIds());
	}

	@Test
	@DisplayName("an unstyled layer carries no attributes beyond its feature ids")
	void tileWithoutStyleCarriesNoAttributes() {
		byte[] mvt = mvtService.renderTile(testLayer.tableName(), 25832, List.of(), List.of(),
				testLayer.zoom(), testLayer.tileX(), testLayer.tileY());

		assertThat(MvtTileDecoder.decode(mvt).get(0).keys()).isEmpty();
	}

	@Test
	@DisplayName("a requested attribute reaches the tile, keyed by its column name")
	void tileCarriesTheRequestedAttribute() {
		byte[] mvt = mvtService.renderTile(testLayer.tableName(), 25832,
				List.of(LayerTableFixture.CATEGORY_COLUMN), List.of(),
				testLayer.zoom(), testLayer.tileX(), testLayer.tileY());

		MvtTileDecoder.Layer decoded = MvtTileDecoder.decode(mvt).get(0);
		assertThat(decoded.keys()).containsExactly(LayerTableFixture.CATEGORY_COLUMN);

		Map<Long, Object> byFeature = decoded.features().stream()
				.collect(Collectors.toMap(MvtTileDecoder.Feature::id,
						feature -> feature.properties().get(LayerTableFixture.CATEGORY_COLUMN)));
		assertThat(byFeature).containsExactlyInAnyOrderEntriesOf(
				new LinkedHashMap<Long, Object>(testLayer.categories()));
	}

	@Test
	@DisplayName("only the requested attributes reach the tile, not every column")
	void tileOmitsAttributesNoStyleAskedFor() {
		byte[] mvt = mvtService.renderTile(testLayer.tableName(), 25832,
				List.of(LayerTableFixture.CATEGORY_COLUMN), List.of(),
				testLayer.zoom(), testLayer.tileX(), testLayer.tileY());

		assertThat(MvtTileDecoder.decode(mvt).get(0).keys())
				.doesNotContain(LayerTableFixture.NUMERIC_COLUMN);
	}

	@Test
	@DisplayName("a tile with no data in range renders to nothing")
	void emptyTileRendersToNull() {
		// z=0 with the corner tile (0,0) is a fixed point far from every possible
		// signal/noise coordinate this fixture ever produces -- no guessing involved.
		byte[] mvt = mvtService.renderTile(testLayer.tableName(), 25832, List.of(), List.of(), 0, 0, 0);
		assertThat(mvt).isNull();
	}

	@Test
	@DisplayName("the tile query uses the GiST index, not a sequential scan")
	void queryPlanIsIndexFriendly() throws Exception {
		assertIndexFriendly(mvtService.explainTile(testLayer.tableName(), 25832, List.of(), List.of(),
				testLayer.zoom(), testLayer.tileX(), testLayer.tileY()));
	}

	@Test
	@DisplayName("selecting style attributes leaves the query plan index-friendly")
	void queryPlanStaysIndexFriendlyWithAttributes() throws Exception {
		assertIndexFriendly(mvtService.explainTile(testLayer.tableName(), 25832,
				List.of(LayerTableFixture.CATEGORY_COLUMN, LayerTableFixture.NUMERIC_COLUMN), List.of(),
				testLayer.zoom(), testLayer.tileX(), testLayer.tileY()));
	}

	@Test
	@DisplayName("a clip mask leaves the query plan index-friendly too, in every mode")
	void queryPlanStaysIndexFriendlyWithAMask() throws Exception {
		String maskTable = LayerTableFixture.create(jdbc, 1).tableName();
		try {
			for (String mode : new String[] { "insideWhole", "insideClipped", "outsideWhole", "outsideClipped" }) {
				assertIndexFriendly(mvtService.explainTile(testLayer.tableName(), 25832, List.of(),
						List.of(new MvtService.ClipMask(maskTable, mode)),
						testLayer.zoom(), testLayer.tileX(), testLayer.tileY()));
			}
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(maskTable)).update();
		}
	}

	/**
	 * CONTRACT.md phase 21: {@code assertIndexFriendly} has to keep holding for a chain
	 * of several masks acting on the same layer at once, not just for one at a time --
	 * each additional mask adds its own join or {@code EXISTS} subquery, and any of them
	 * could in principle force a sequential scan on the layer table if built wrong.
	 */
	@Test
	@DisplayName("a chain of several masks together still leaves the query plan index-friendly")
	void queryPlanStaysIndexFriendlyWithAChainOfMasks() throws Exception {
		String maskTableA = LayerTableFixture.create(jdbc, 1).tableName();
		String maskTableB = LayerTableFixture.create(jdbc, 1).tableName();
		try {
			assertIndexFriendly(mvtService.explainTile(testLayer.tableName(), 25832, List.of(),
					List.of(new MvtService.ClipMask(maskTableA, "insideClipped"),
							new MvtService.ClipMask(maskTableB, "outsideWhole")),
					testLayer.zoom(), testLayer.tileX(), testLayer.tileY()));
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(maskTableA)).update();
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(maskTableB)).update();
		}
	}

	private void assertIndexFriendly(String json) throws Exception {
		JsonNode plan = objectMapper.readTree(json).get(0).get("Plan");
		List<JsonNode> nodes = new ArrayList<>();
		collectNodes(plan, nodes);

		boolean seqScanOnLayerTable = nodes.stream().anyMatch(node ->
				"Seq Scan".equals(text(node, "Node Type"))
						&& testLayer.tableName().equals(text(node, "Relation Name")));
		boolean usesIndex = nodes.stream()
				.map(node -> text(node, "Node Type"))
				.anyMatch(type -> type != null && type.contains("Index"));

		assertThat(seqScanOnLayerTable)
				.as("kein Seq Scan auf der Layertabelle erwartet, Plan war:%n%s", json)
				.isFalse();
		assertThat(usesIndex)
				.as("Index Scan (GiST) erwartet, Plan war:%n%s", json)
				.isTrue();
	}

	private static void collectNodes(JsonNode node, List<JsonNode> out) {
		if (node == null || node.isMissingNode()) {
			return;
		}
		out.add(node);
		JsonNode children = node.get("Plans");
		if (children != null && children.isArray()) {
			children.forEach(child -> collectNodes(child, out));
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null ? null : value.asString();
	}
}
