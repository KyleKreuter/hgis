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
 * Proves that {@link MvtService#renderTile} actually clips against a mask table
 * (CONTRACT.md phase 19), not just filters whole features: a feature straddling the
 * mask edge has to come back smaller, one entirely outside the mask has to vanish, and
 * one crossing two mask polygons has to appear exactly once -- the {@code ST_Union} in
 * {@code MvtService}'s clipped query is what makes that last one true.
 *
 * Every fixture geometry is placed as a fraction of one fixed tile's native bounding
 * box, computed once in {@link #computeTile()}, rather than as raw metre offsets from a
 * point: that keeps every feature safely inside the same tile regardless of where
 * exactly the anchor point happens to sit relative to the tile grid.
 *
 * Whether z-index decides which layers get clipped at all is {@link TileController}'s
 * job, not {@link MvtService}'s, and is covered by {@code TileControllerClipTest}.
 * Whether the clipped query stays index-friendly is covered by
 * {@link MvtServiceTest#queryPlanStaysIndexFriendlyWithAMask()}.
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
		bounds = jdbc.sql("""
				SELECT ST_XMin(t) AS xmin, ST_YMin(t) AS ymin, ST_XMax(t) AS xmax, ST_YMax(t) AS ymax
				FROM (SELECT ST_Transform(ST_TileEnvelope(:z, :x, :y), :srid) AS t) s
				""")
				.param("z", ZOOM)
				.param("x", tileX)
				.param("y", tileY)
				.param("srid", SRID)
				.query((rs, rowNum) -> new double[] {
						rs.getDouble("xmin"), rs.getDouble("ymin"), rs.getDouble("xmax"), rs.getDouble("ymax") })
				.single();
	}

	@AfterEach
	void tearDown() {
		for (String table : tablesToDrop) {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(table)).update();
		}
		tablesToDrop.clear();
	}

	@Test
	@DisplayName("a feature straddling the mask edge comes out with a smaller area than unclipped")
	void clippingReducesAStraddlingFeaturesArea() {
		String layerTable = createTable();
		String maskTable = createTable();

		// The layer feature is a square; the mask covers only its left half, so roughly
		// half the area should survive the clip.
		insertRectangle(layerTable, fx(0.20), fy(0.20), fx(0.40), fy(0.40));
		insertRectangle(maskTable, fx(0.20), fy(0.20), fx(0.30), fy(0.40));

		double unclippedArea = firstFeature(render(layerTable, null)).area();
		double clippedArea = firstFeature(render(layerTable, maskTable)).area();

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
	@DisplayName("a feature entirely outside the mask is missing from the clipped tile")
	void anObjectOutsideTheMaskIsMissing() {
		String layerTable = createTable();
		String maskTable = createTable();

		// Far from the mask below, but still inside the same tile, so the unclipped
		// render is the control that proves the feature would otherwise be visible.
		insertRectangle(layerTable, fx(0.70), fy(0.70), fx(0.75), fy(0.75));
		insertRectangle(maskTable, fx(0.20), fy(0.20), fx(0.30), fy(0.40));

		assertThat(render(layerTable, null)).isNotNull();
		assertThat(render(layerTable, maskTable)).isNull();
	}

	@Test
	@DisplayName("a feature crossing two overlapping mask polygons appears exactly once, not twice")
	void anObjectCrossingTwoMaskPolygonsAppearsOnce() {
		String layerTable = createTable();
		String maskTable = createTable();

		long fid = insertRectangle(layerTable, fx(0.10), fy(0.10), fx(0.90), fy(0.20));
		// Two mask polygons that overlap each other between fx 0.40 and 0.60, and both
		// intersect the layer feature above. Without ST_Union in MvtService's clipped
		// query, the join would produce two rows for the same fid here.
		insertRectangle(maskTable, fx(0.10), fy(0.10), fx(0.60), fy(0.20));
		insertRectangle(maskTable, fx(0.40), fy(0.10), fx(0.90), fy(0.20));

		MvtTileDecoder.Layer layer = MvtTileDecoder.decode(render(layerTable, maskTable)).get(0);
		assertThat(layer.featureIds()).containsExactly(fid);
		assertThat(layer.features()).hasSize(1);
	}

	// --- fixture helpers ---------------------------------------------------------

	private double fx(double fraction) {
		return bounds[0] + fraction * (bounds[2] - bounds[0]);
	}

	private double fy(double fraction) {
		return bounds[1] + fraction * (bounds[3] - bounds[1]);
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

	private byte[] render(String layerTable, String maskTable) {
		return mvtService.renderTile(layerTable, SRID, List.of(), maskTable, ZOOM, tileX, tileY);
	}

	private static MvtTileDecoder.Feature firstFeature(byte[] mvt) {
		return MvtTileDecoder.decode(mvt).get(0).features().get(0);
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
