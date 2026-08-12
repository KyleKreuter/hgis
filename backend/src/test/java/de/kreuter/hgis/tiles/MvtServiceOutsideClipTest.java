package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
 * Proves that {@link MvtService#renderTile} clips correctly in {@code outside} mode
 * (CONTRACT.md phase 20): the complement of {@code inside} mode, covered by
 * {@code MvtServiceClipTest}. Two traps make {@code outside} easy to get backwards, and
 * this class has one dedicated test for each:
 *
 * <ol>
 *   <li>{@link #objectNotTouchingTheMaskIsUnaffected()} -- the {@code l.geom && mask.geom}
 *       predicate that is correct for {@code inside} would, if left in place for
 *       {@code outside}, keep only features touching the mask and drop everything meant
 *       to survive untouched.</li>
 *   <li>{@link #tileWithNoMaskPortionShowsEverything()} -- a tile the mask never reaches
 *       has to show its layer whole, not empty, even though {@code ST_Union} yields
 *       {@code NULL} there.</li>
 * </ol>
 *
 * Every fixture geometry is placed as a fraction of one fixed tile's native bounding
 * box, the same approach {@code MvtServiceClipTest} uses, duplicated locally since that
 * class derives its tile from its own anchor point.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MvtServiceOutsideClipTest {

	private static final int SRID = 25832;
	private static final int ZOOM = 10;

	/** A different anchor than MvtServiceClipTest's, so the two classes never share a tile. */
	private static final double ANCHOR_X = 720_000;
	private static final double ANCHOR_Y = 5_820_000;

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

	/** Trap 1: the {@code l.geom && mask.geom} predicate would be exactly backwards here. */
	@Test
	@DisplayName("trap 1: a feature that never touches the mask stays fully intact in outside mode")
	void objectNotTouchingTheMaskIsUnaffected() {
		String layerTable = createTable();
		String maskTable = createTable();

		// Far from the mask, with no shared bounding box at all -- the case the && predicate
		// would wrongly drop if it survived into the outside query.
		insertRectangle(layerTable, fx(0.70), fy(0.70), fx(0.80), fy(0.80));
		insertRectangle(maskTable, fx(0.10), fy(0.10), fx(0.20), fy(0.20));

		double unclippedArea = firstFeature(render(layerTable, null)).area();
		byte[] outside = render(layerTable, maskTable, "outside");

		assertThat(outside).as("Objekt außerhalb der Maske darf nicht verschwinden").isNotNull();
		assertThat(firstFeature(outside).area()).isEqualTo(unclippedArea);
	}

	/** Trap 2: ST_Difference(geom, NULL) is NULL, and must not be allowed to empty the tile. */
	@Test
	@DisplayName("trap 2: a tile the mask never reaches shows its layer whole in outside mode")
	void tileWithNoMaskPortionShowsEverything() {
		String layerTable = createTable();
		String maskTable = createTable();

		insertRectangle(layerTable, fx(0.30), fy(0.30), fx(0.60), fy(0.60));
		// Well outside this tile's bounds -- ST_Union over zero matching rows yields NULL.
		double far = bounds[2] + (bounds[2] - bounds[0]) * 5;
		insertRectangle(maskTable, far, far, far + 100, far + 100);

		byte[] unclipped = render(layerTable, null);
		byte[] outside = render(layerTable, maskTable, "outside");

		assertThat(outside).isNotNull();
		assertThat(firstFeature(outside).area()).isEqualTo(firstFeature(unclipped).area());
	}

	@Test
	@DisplayName("an object entirely inside the mask disappears in outside mode")
	void objectEntirelyInsideTheMaskDisappears() {
		String layerTable = createTable();
		String maskTable = createTable();

		insertRectangle(layerTable, fx(0.42), fy(0.42), fx(0.48), fy(0.48));
		insertRectangle(maskTable, fx(0.20), fy(0.20), fx(0.80), fy(0.80));

		assertThat(render(layerTable, null)).isNotNull();
		assertThat(render(layerTable, maskTable, "outside")).isNull();
	}

	/**
	 * Checks the area, not the object count, as CONTRACT.md phase 20 asks: the inside
	 * clip and the outside clip of the very same straddling feature and mask have to add
	 * back up to the unclipped area, since together they cover it exactly once.
	 */
	@Test
	@DisplayName("an object straddling the edge keeps its outer part, complementary to the inside clip")
	void objectStraddlingTheEdgeKeepsTheOuterPart() {
		String layerTable = createTable();
		String maskTable = createTable();

		insertRectangle(layerTable, fx(0.20), fy(0.20), fx(0.40), fy(0.40));
		insertRectangle(maskTable, fx(0.20), fy(0.20), fx(0.30), fy(0.40));

		double unclippedArea = firstFeature(render(layerTable, null)).area();
		double insideArea = firstFeature(render(layerTable, maskTable, "inside")).area();
		double outsideArea = firstFeature(render(layerTable, maskTable, "outside")).area();

		assertThat(outsideArea)
				.as("geklippte Fläche außerhalb muss kleiner als die ungeklippte sein")
				.isLessThan(unclippedArea);
		assertThat(outsideArea).isPositive();
		assertThat(insideArea + outsideArea)
				.as("innerer und äußerer Teil müssen zusammen wieder die volle Fläche ergeben")
				.isCloseTo(unclippedArea, within(unclippedArea * 0.01));
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

	private void insertRectangle(String tableName, double x0, double y0, double x1, double y1) {
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				INSERT INTO %s (geom)
				VALUES (ST_Multi(ST_MakeEnvelope(:x0, :y0, :x1, :y1, 25832)))
				""".formatted(table))
				.param("x0", x0)
				.param("y0", y0)
				.param("x1", x1)
				.param("y1", y1)
				.update();
	}

	private byte[] render(String layerTable, String maskTable) {
		return render(layerTable, maskTable, null);
	}

	private byte[] render(String layerTable, String maskTable, String clipMode) {
		return mvtService.renderTile(layerTable, SRID, List.of(), maskTable, clipMode, ZOOM, tileX, tileY);
	}

	private static MvtTileDecoder.Feature firstFeature(byte[] mvt) {
		return MvtTileDecoder.decode(mvt).get(0).features().get(0);
	}

	/**
	 * Derives the WGS84 lng/lat of a native-CRS point via PostGIS itself and turns it
	 * into the XYZ tile index {@code ST_TileEnvelope} uses -- same approach as
	 * {@code LayerTableFixture} and {@code MvtServiceClipTest}, duplicated locally for
	 * this class's own anchor point.
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
