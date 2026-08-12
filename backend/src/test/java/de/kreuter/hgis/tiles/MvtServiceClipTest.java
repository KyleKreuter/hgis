package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Proves that {@link MvtService#renderTile} actually clips against mask tables in the
 * two "inside" modes, {@code insideClipped} and {@code insideWhole} (CONTRACT.md phase
 * 19/21), not just filters whole features where clipping was meant, or clips where
 * keeping-whole was meant: a feature straddling the {@code insideClipped} mask edge has
 * to come back smaller, one entirely outside has to vanish, and one crossing two mask
 * polygons has to appear exactly once -- the {@code ST_Union} in {@code MvtService}'s
 * clipped query is what makes that last one true. A feature straddling an {@code
 * insideWhole} mask's edge instead has to come back at its full, unclipped area, since
 * that mode never cuts geometry, only decides whether a feature is shown at all. This
 * class also covers what happens when several masks act on the same layer at once,
 * across both {@code *Clipped} modes and across a mask acting on another mask. The
 * {@code outside} modes are covered separately, by {@code MvtServiceOutsideClipTest}.
 *
 * Every fixture geometry is placed as a fraction of one fixed tile's native bounding
 * box, computed once in {@link #computeTile()}, rather than as raw metre offsets from a
 * point: that keeps every feature safely inside the same tile regardless of where
 * exactly the anchor point happens to sit relative to the tile grid. The one test that
 * needs two adjacent tiles, {@link #insideWholeKeepsAFeatureWholeAcrossATileItDoesNotTouch()},
 * computes a second tile's bounds locally instead.
 *
 * Whether z-index decides which layers get clipped at all, and by which masks, is
 * {@link TileController}'s job, not {@link MvtService}'s, and is covered by
 * {@code TileControllerClipTest}. Whether the clipped query stays index-friendly,
 * including for a chain of several masks and for the {@code *Whole} modes, is covered
 * by {@link MvtServiceTest#queryPlanStaysIndexFriendlyWithAMask()} and
 * {@link MvtServiceTest#queryPlanStaysIndexFriendlyWithAChainOfMasks()}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MvtServiceClipTest {

	private static final int SRID = 25832;
	private static final int ZOOM = 10;

	/** Arbitrary point well inside German UTM32N, just to anchor a real tile. */
	private static final double ANCHOR_X = 700_000;
	private static final double ANCHOR_Y = 5_800_000;

	@Autowired
	private MvtService mvtService;

	@Autowired
	private JdbcClient jdbc;

	private int tileX;
	private int tileY;

	/** [xmin, ymin, xmax, ymax] of the fixed tile, in {@link #SRID}. */
	private double[] bounds;

	private final List<String> tablesToDrop = new ArrayList<>();

	@BeforeAll
	void computeTile() {
		int[] tile = tileForNativePoint(ANCHOR_X, ANCHOR_Y, ZOOM);
		tileX = tile[0];
		tileY = tile[1];
		bounds = nativeBoundsOfTile(tileX, tileY, ZOOM);
	}

	@AfterEach
	void tearDown() {
		for (String table : tablesToDrop) {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(table)).update();
		}
		tablesToDrop.clear();
	}

	@Test
	@DisplayName("insideClipped: a feature straddling the mask edge comes out with a smaller area than unclipped")
	void insideClippedReducesAStraddlingFeaturesArea() {
		String layerTable = createTable();
		String maskTable = createTable();

		// The layer feature is a square; the mask covers only its left half, so roughly
		// half the area should survive the clip.
		insertRectangle(layerTable, fx(0.20), fy(0.20), fx(0.40), fy(0.40));
		insertRectangle(maskTable, fx(0.20), fy(0.20), fx(0.30), fy(0.40));

		double unclippedArea = firstFeature(render(layerTable, List.of())).area();
		double clippedArea = firstFeature(render(layerTable, List.of(mask(maskTable, "insideClipped")))).area();

		assertThat(clippedArea)
				.as("geklippte Fläche muss kleiner als die ungeklippte sein")
				.isLessThan(unclippedArea);
		assertThat(clippedArea).isPositive();
		// Generous band around the geometric 50% expectation -- exact equality would be
		// fragile against ST_AsMVTGeom's tile-grid snapping, but a result far off half
		// would mean the clip geometry, not just its existence, is wrong.
		assertThat(clippedArea).isBetween(unclippedArea * 0.3, unclippedArea * 0.7);
	}

	@Test
	@DisplayName("insideClipped: a feature entirely outside the mask is missing from the clipped tile")
	void insideClippedDropsAnObjectOutsideTheMask() {
		String layerTable = createTable();
		String maskTable = createTable();

		// Far from the mask below, but still inside the same tile, so the unclipped
		// render is the control that proves the feature would otherwise be visible.
		insertRectangle(layerTable, fx(0.70), fy(0.70), fx(0.75), fy(0.75));
		insertRectangle(maskTable, fx(0.20), fy(0.20), fx(0.30), fy(0.40));

		assertThat(render(layerTable, List.of())).isNotNull();
		assertThat(render(layerTable, List.of(mask(maskTable, "insideClipped")))).isNull();
	}

	@Test
	@DisplayName("insideClipped: a feature crossing two overlapping mask polygons appears exactly once, not twice")
	void insideClippedFeatureCrossingTwoMaskPolygonsAppearsOnce() {
		String layerTable = createTable();
		String maskTable = createTable();

		long fid = insertRectangle(layerTable, fx(0.10), fy(0.10), fx(0.90), fy(0.20));
		// Two mask polygons that overlap each other between fx 0.40 and 0.60, and both
		// intersect the layer feature above. Without ST_Union in MvtService's clipped
		// query, the join would produce two rows for the same fid here.
		insertRectangle(maskTable, fx(0.10), fy(0.10), fx(0.60), fy(0.20));
		insertRectangle(maskTable, fx(0.40), fy(0.10), fx(0.90), fy(0.20));

		MvtTileDecoder.Layer layer =
				MvtTileDecoder.decode(render(layerTable, List.of(mask(maskTable, "insideClipped")))).get(0);
		assertThat(layer.featureIds()).containsExactly(fid);
		assertThat(layer.features()).hasSize(1);
	}

	/** CONTRACT.md phase 21, requirement 1: insideWhole never cuts geometry, only filters. */
	@Test
	@DisplayName("insideWhole: a feature straddling the mask edge keeps its full, unclipped area")
	void insideWholeKeepsAStraddlingFeatureAtFullArea() {
		String layerTable = createTable();
		String maskTable = createTable();

		insertRectangle(layerTable, fx(0.20), fy(0.20), fx(0.40), fy(0.40));
		insertRectangle(maskTable, fx(0.20), fy(0.20), fx(0.30), fy(0.40));

		double unclippedArea = firstFeature(render(layerTable, List.of())).area();
		double wholeArea = firstFeature(render(layerTable, List.of(mask(maskTable, "insideWhole")))).area();

		assertThat(wholeArea).isEqualTo(unclippedArea);
	}

	@Test
	@DisplayName("insideWhole: a feature that never touches the mask is dropped entirely")
	void insideWholeDropsAFeatureThatNeverTouchesTheMask() {
		String layerTable = createTable();
		String maskTable = createTable();

		insertRectangle(layerTable, fx(0.70), fy(0.70), fx(0.75), fy(0.75));
		insertRectangle(maskTable, fx(0.20), fy(0.20), fx(0.30), fy(0.40));

		assertThat(render(layerTable, List.of())).isNotNull();
		assertThat(render(layerTable, List.of(mask(maskTable, "insideWhole")))).isNull();
	}

	/**
	 * CONTRACT.md phase 21, requirement 3: over the same mask, insideWhole and
	 * outsideWhole together account for exactly every feature, with no feature in both
	 * and none in neither -- a straddling feature counts as touching (the boundary rule:
	 * {@code ST_Intersects} includes the edge), so it belongs to insideWhole, and a
	 * feature with no shared point at all belongs to outsideWhole.
	 */
	@Test
	@DisplayName("insideWhole and outsideWhole over the same mask partition every feature, with no overlap")
	void insideAndOutsideWholeTogetherPartitionAllFeatures() {
		String layerTable = createTable();
		String maskTable = createTable();

		long straddling = insertRectangle(layerTable, fx(0.20), fy(0.20), fx(0.40), fy(0.40));
		long untouched = insertRectangle(layerTable, fx(0.70), fy(0.70), fx(0.75), fy(0.75));
		insertRectangle(maskTable, fx(0.20), fy(0.20), fx(0.30), fy(0.40));

		List<Long> insideIds =
				MvtTileDecoder.decode(render(layerTable, List.of(mask(maskTable, "insideWhole")))).get(0).featureIds();
		List<Long> outsideIds =
				MvtTileDecoder.decode(render(layerTable, List.of(mask(maskTable, "outsideWhole")))).get(0).featureIds();

		assertThat(insideIds).containsExactly(straddling);
		assertThat(outsideIds).containsExactly(untouched);
	}

	/**
	 * CONTRACT.md phase 21, requirement 4: whether a feature touches an {@code
	 * insideWhole} mask is a property of the whole feature, never of the tile it is
	 * rendered into. A feature spanning two tiles, touching the mask only within one of
	 * them, must still appear -- with its own portion -- in both.
	 *
	 * <p>This is the test the {@code EXISTS} against the mask's full table in {@code
	 * MvtService} exists for. A wrong implementation that instead filters against a
	 * tile-bounded union of the mask (the shortcut correctly taken for the {@code
	 * *Clipped} modes) would find no mask polygon in the second tile at all and drop the
	 * feature there, even though it is kept whole in the first.
	 */
	@Test
	@DisplayName("insideWhole: a feature spanning two tiles, touching the mask in only one, appears in both")
	void insideWholeKeepsAFeatureWholeAcrossATileItDoesNotTouch() {
		String layerTable = createTable();
		String maskTable = createTable();

		int[] eastTile = { tileX + 1, tileY };
		double[] eastBounds = nativeBoundsOfTile(eastTile[0], eastTile[1], ZOOM);

		// The feature spans from 80% across the first (fixed) tile to 20% across its
		// east neighbour -- straddling the shared tile boundary.
		double x0 = bounds[0] + 0.80 * (bounds[2] - bounds[0]);
		double x1 = eastBounds[0] + 0.20 * (eastBounds[2] - eastBounds[0]);
		double y0 = fy(0.40);
		double y1 = fy(0.60);
		insertRectangle(layerTable, x0, y0, x1, y1);

		// The mask sits entirely within the first tile, comfortably clear of the shared
		// tile boundary (unlike the feature, which has to reach right up to it), while
		// still overlapping the feature's own portion within the first tile.
		insertRectangle(maskTable, fx(0.80), fy(0.40), fx(0.90), fy(0.60));

		List<MvtService.ClipMask> masks = List.of(mask(maskTable, "insideWhole"));
		byte[] firstTile = mvtService.renderTile(layerTable, SRID, List.of(), masks, ZOOM, tileX, tileY);
		byte[] secondTile = mvtService.renderTile(layerTable, SRID, List.of(), masks, ZOOM, eastTile[0], eastTile[1]);

		assertThat(firstTile).as("Objekt muss in der ersten Kachel erscheinen, dort berührt es die Maske").isNotNull();
		assertThat(secondTile)
				.as("Objekt muss auch in der zweiten Kachel erscheinen, obwohl die Maske dort nicht liegt")
				.isNotNull();
	}

	/**
	 * CONTRACT.md phase 21, requirement 5 (first half): two insideClipped masks combine
	 * as their intersection -- only the part of the feature inside both survives.
	 */
	@Test
	@DisplayName("two insideClipped masks combine as their intersection")
	void twoInsideClippedMasksCombineAsTheirIntersection() {
		String layerTable = createTable();
		String maskTableA = createTable();
		String maskTableB = createTable();

		insertRectangle(layerTable, fx(0.10), fy(0.10), fx(0.90), fy(0.90));
		insertRectangle(maskTableA, fx(0.10), fy(0.10), fx(0.60), fy(0.90));
		insertRectangle(maskTableB, fx(0.40), fy(0.10), fx(0.90), fy(0.90));

		double unclippedArea = firstFeature(render(layerTable, List.of())).area();
		double combinedArea = firstFeature(render(layerTable,
				List.of(mask(maskTableA, "insideClipped"), mask(maskTableB, "insideClipped")))).area();

		// The two masks overlap only between fx 0.40 and 0.60 -- a fifth of the layer
		// feature's own 0.10-0.90 span -- so the combined clip should land well under
		// either mask's own share, not just under the smaller of the two.
		assertThat(combinedArea).isPositive();
		assertThat(combinedArea).isLessThan(unclippedArea * 0.35);
	}

	/**
	 * CONTRACT.md phase 21, requirement 5 (second half): an insideClipped mask plus an
	 * outsideClipped mask combine as the inside of the one without the other.
	 */
	@Test
	@DisplayName("an insideClipped and an outsideClipped mask combine as the inside of one without the other")
	void insideClippedAndOutsideClippedMasksCombine() {
		String layerTable = createTable();
		String insideMaskTable = createTable();
		String outsideMaskTable = createTable();

		insertRectangle(layerTable, fx(0.10), fy(0.10), fx(0.90), fy(0.90));
		insertRectangle(insideMaskTable, fx(0.10), fy(0.10), fx(0.60), fy(0.90));
		insertRectangle(outsideMaskTable, fx(0.40), fy(0.10), fx(0.90), fy(0.90));

		double insideOnlyArea = firstFeature(render(layerTable, List.of(mask(insideMaskTable, "insideClipped")))).area();
		double combinedArea = firstFeature(render(layerTable,
				List.of(mask(insideMaskTable, "insideClipped"), mask(outsideMaskTable, "outsideClipped")))).area();

		// Subtracting the outside mask's share (fx 0.40-0.60 of the inside mask's own
		// fx 0.10-0.60 area) must shrink the result below what the inside mask alone left.
		assertThat(combinedArea).isPositive();
		assertThat(combinedArea).isLessThan(insideOnlyArea);
	}

	/**
	 * CONTRACT.md phase 21, requirement 6: the same masks in a different list order
	 * produce the same clipped area -- intersection and difference are set operations
	 * and commute, so the caller's ordering must not matter.
	 */
	@Test
	@DisplayName("combining an insideClipped and an outsideClipped mask is independent of their order in the list")
	void combiningTwoMasksIsOrderIndependent() {
		String layerTable = createTable();
		String insideMaskTable = createTable();
		String outsideMaskTable = createTable();

		insertRectangle(layerTable, fx(0.10), fy(0.10), fx(0.90), fy(0.90));
		insertRectangle(insideMaskTable, fx(0.10), fy(0.10), fx(0.60), fy(0.90));
		insertRectangle(outsideMaskTable, fx(0.40), fy(0.10), fx(0.90), fy(0.90));

		MvtService.ClipMask insideMask = mask(insideMaskTable, "insideClipped");
		MvtService.ClipMask outsideMask = mask(outsideMaskTable, "outsideClipped");

		double areaInOrder = firstFeature(render(layerTable, List.of(insideMask, outsideMask))).area();
		double areaReversed = firstFeature(render(layerTable, List.of(outsideMask, insideMask))).area();

		assertThat(areaReversed).isEqualTo(areaInOrder);
	}

	/**
	 * CONTRACT.md phase 21, requirement 7: a mask is a layer like any other, so a mask
	 * sitting above another mask is itself cut by the one below it. {@code MvtService}
	 * has no notion of "mask" versus "layer" at all -- this only exercises the same
	 * {@code renderTile} call with the upper mask's own table as the thing being
	 * rendered, to document that the rule holds in practice, not only in theory.
	 */
	@Test
	@DisplayName("a mask above another mask is itself clipped by the one below it")
	void aMaskAboveAnotherMaskIsItselfClipped() {
		String upperMaskTable = createTable();
		String lowerMaskTable = createTable();

		insertRectangle(upperMaskTable, fx(0.20), fy(0.20), fx(0.40), fy(0.40));
		insertRectangle(lowerMaskTable, fx(0.20), fy(0.20), fx(0.30), fy(0.40));

		double unclippedArea = firstFeature(render(upperMaskTable, List.of())).area();
		double clippedArea =
				firstFeature(render(upperMaskTable, List.of(mask(lowerMaskTable, "insideClipped")))).area();

		assertThat(clippedArea).isLessThan(unclippedArea);
	}

	// --- fixture helpers ---------------------------------------------------------

	private double fx(double fraction) {
		return bounds[0] + fraction * (bounds[2] - bounds[0]);
	}

	private double fy(double fraction) {
		return bounds[1] + fraction * (bounds[3] - bounds[1]);
	}

	private static MvtService.ClipMask mask(String tableName, String mode) {
		return new MvtService.ClipMask(tableName, mode);
	}

	private String createTable() {
		String tableName = SqlIdentifier.tableName(UUID.randomUUID());
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX ON %s USING GIST (geom)".formatted(table)).update();
		tablesToDrop.add(tableName);
		return tableName;
	}

	private long insertRectangle(String tableName, double x0, double y0, double x1, double y1) {
		String table = SqlIdentifier.quoteLayerTable(tableName);
		return jdbc.sql("""
				INSERT INTO %s (geom)
				VALUES (ST_Multi(ST_MakeEnvelope(:x0, :y0, :x1, :y1, 25832)))
				RETURNING fid
				""".formatted(table))
				.param("x0", x0)
				.param("y0", y0)
				.param("x1", x1)
				.param("y1", y1)
				.query(Long.class)
				.single();
	}

	private byte[] render(String layerTable, List<MvtService.ClipMask> masks) {
		return mvtService.renderTile(layerTable, SRID, List.of(), masks, ZOOM, tileX, tileY);
	}

	private static MvtTileDecoder.Feature firstFeature(byte[] mvt) {
		return MvtTileDecoder.decode(mvt).get(0).features().get(0);
	}

	private double[] nativeBoundsOfTile(int x, int y, int zoom) {
		return jdbc.sql("""
				SELECT ST_XMin(t) AS xmin, ST_YMin(t) AS ymin, ST_XMax(t) AS xmax, ST_YMax(t) AS ymax
				FROM (SELECT ST_Transform(ST_TileEnvelope(:z, :x, :y), :srid) AS t) s
				""")
				.param("z", zoom)
				.param("x", x)
				.param("y", y)
				.param("srid", SRID)
				.query((rs, rowNum) -> new double[] {
						rs.getDouble("xmin"), rs.getDouble("ymin"), rs.getDouble("xmax"), rs.getDouble("ymax") })
				.single();
	}

	/**
	 * Derives the WGS84 lng/lat of a native-CRS point via PostGIS itself and turns it
	 * into the XYZ tile index {@code ST_TileEnvelope} uses -- same approach as
	 * {@code LayerTableFixture}, duplicated locally since that one derives its tile from
	 * its own signal cluster, not this class's fixed anchor point.
	 */
	private int[] tileForNativePoint(double nativeX, double nativeY, int zoom) {
		double[] lngLat = jdbc.sql("""
				SELECT ST_X(t) AS lng, ST_Y(t) AS lat
				FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(:x, :y), 25832), 4326) AS t) s
				""")
				.param("x", nativeX)
				.param("y", nativeY)
				.query((rs, rowNum) -> new double[] { rs.getDouble("lng"), rs.getDouble("lat") })
				.single();

		double latRad = Math.toRadians(lngLat[1]);
		int n = 1 << zoom;
		int x = (int) Math.floor((lngLat[0] + 180.0) / 360.0 * n);
		int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
		return new int[] { x, y };
	}
}
