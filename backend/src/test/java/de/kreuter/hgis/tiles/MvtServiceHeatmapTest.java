package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The heatmap package's core: when a layer's tile is rendered with {@code heatmap} set,
 * {@link MvtService} carries points instead of the layer's own geometry (CONTRACT.md
 * heatmap package, "Punkte im Kachelweg").
 *
 * <p>Every table here is built ad hoc per test, in whatever geometry type that test
 * needs -- unlike {@link MvtServiceTest}, which shares one polygon fixture throughout.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MvtServiceHeatmapTest {

	/** A fixed tile footprint near Hamburg, inside what EPSG:25832 can describe (close to UTM32's own meridian). */
	private static final double CENTER_LON = 9.0;
	private static final double CENTER_LAT = 53.55;

	@Autowired
	private MvtService mvtService;

	@Autowired
	private JdbcClient jdbc;

	private final List<String> tablesToDrop = new ArrayList<>();

	@AfterEach
	void tearDown() {
		tablesToDrop.forEach(table ->
				jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(table)).update());
		tablesToDrop.clear();
	}

	// --- MULTIPOINT: unchanged -------------------------------------------------------

	@Test
	@DisplayName("a point layer's heatmap tile carries the very same points as its ordinary tile")
	void pointLayerIsUnchanged() {
		String tableName = createTable("geom geometry(MultiPoint, 25832) NOT NULL, wert integer");
		int[] tile = tileAt(8);
		double[] bounds = tileLonLatBounds(tile[0], tile[1], tile[2]);
		double midLon = (bounds[0] + bounds[2]) / 2;
		double midLat = (bounds[1] + bounds[3]) / 2;
		insert(tableName, "ST_Multi(ST_Transform(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), 25832))",
				"wert", 42, midLon, midLat);

		byte[] plain = mvtService.renderTile(tableName, 25832, List.of("wert"), List.of(),
				GeometryType.MULTIPOINT, false, tile[0], tile[1], tile[2]).mvt();
		byte[] heatmap = mvtService.renderTile(tableName, 25832, List.of("wert"), List.of(),
				GeometryType.MULTIPOINT, true, tile[0], tile[1], tile[2]).mvt();

		MvtTileDecoder.Feature plainFeature = MvtTileDecoder.decode(plain).get(0).features().get(0);
		MvtTileDecoder.Feature heatmapFeature = MvtTileDecoder.decode(heatmap).get(0).features().get(0);
		// usingRecursiveComparison, not isEqualTo: a ring is a List<long[]>, and long[]
		// inherits Object.equals -- reference equality -- so a plain isEqualTo would fail
		// on every pair of freshly decoded, value-identical arrays.
		assertThat(heatmapFeature.rings()).usingRecursiveComparison().isEqualTo(plainFeature.rings());
		assertThat(heatmapFeature.properties()).isEqualTo(plainFeature.properties());
	}

	// --- MULTIPOLYGON: one point per feature, guaranteed inside -----------------------

	/**
	 * A U-shape (three sides of a square, the middle third of the fourth missing) around
	 * a fixed centre: its centroid sits in the empty notch, outside the polygon, which is
	 * exactly the case the contract names for why {@code ST_PointOnSurface} has to be
	 * used instead of {@code ST_Centroid}.
	 */
	@Test
	@DisplayName("a polygon's heatmap point always lies on the polygon -- proven on a shape whose centroid does not")
	void polygonHeatmapPointLiesOnASurfaceCentroidWouldMiss() {
		String tableName = createTable("geom geometry(MultiPolygon, 25832) NOT NULL, wert integer");
		int[] tile = tileAt(10);
		double[] bounds = tileLonLatBounds(tile[0], tile[1], tile[2]);

		// A "U"/staple shape opening upward: a thin bottom bar (0..0.2 of the height)
		// joining two thin legs (0..0.2 and 0.8..1.0 of the width) that reach to the top.
		// Worked out by hand (areas and first moments of the three rectangles) before
		// writing this: the centroid sits at roughly (0.5, 0.41) of the bounding square --
		// above the bar, between the legs, squarely in the empty notch a bounding-box
		// centroid would not know to avoid.
		double x0 = lerp(bounds[0], bounds[2], 0.0);
		double x1 = lerp(bounds[0], bounds[2], 0.2);
		double x2 = lerp(bounds[0], bounds[2], 0.8);
		double x3 = lerp(bounds[0], bounds[2], 1.0);
		double y0 = lerp(bounds[1], bounds[3], 0.0);
		double y1 = lerp(bounds[1], bounds[3], 0.2);
		double y2 = lerp(bounds[1], bounds[3], 1.0);

		// java.util.Locale.ROOT explicitly: %f otherwise follows the JVM's default locale,
		// which on a German system prints a decimal comma -- invalid WKT.
		String uShapeWkt = String.format(java.util.Locale.ROOT,
				"POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f, %f %f, %f %f, %f %f, %f %f))",
				x0, y0, x3, y0, x3, y2, x2, y2, x2, y1, x1, y1, x1, y2, x0, y2, x0, y0);

		// Sanity check on the fixture itself, independent of MvtService: the shape really
		// does defeat ST_Centroid and really is one ST_PointOnSurface calls on can fix.
		boolean centroidInside = jdbc.sql(
				"SELECT ST_Contains(ST_GeomFromText(:wkt, 4326), ST_Centroid(ST_GeomFromText(:wkt, 4326)))")
				.param("wkt", uShapeWkt).query(Boolean.class).single();
		boolean pointOnSurfaceInside = jdbc.sql(
				"SELECT ST_Contains(ST_GeomFromText(:wkt, 4326), ST_PointOnSurface(ST_GeomFromText(:wkt, 4326)))")
				.param("wkt", uShapeWkt).query(Boolean.class).single();
		assertThat(centroidInside).as("die Testform muss den Zentroiden wirklich aus der Flaeche treiben").isFalse();
		assertThat(pointOnSurfaceInside).isTrue();

		jdbc.sql(("INSERT INTO %s (geom, wert) VALUES "
				+ "(ST_Multi(ST_Transform(ST_SetSRID(ST_GeomFromText(:wkt), 4326), 25832)), :wert)")
				.formatted(SqlIdentifier.quoteLayerTable(tableName)))
				.param("wkt", uShapeWkt).param("wert", 7).update();

		byte[] plain = mvtService.renderTile(tableName, 25832, List.of(), List.of(),
				GeometryType.MULTIPOLYGON, false, tile[0], tile[1], tile[2]).mvt();
		byte[] heatmap = mvtService.renderTile(tableName, 25832, List.of("wert"), List.of(),
				GeometryType.MULTIPOLYGON, true, tile[0], tile[1], tile[2]).mvt();

		MvtTileDecoder.Feature plainFeature = MvtTileDecoder.decode(plain).get(0).features().get(0);
		assertThat(plainFeature.pointCount())
				.as("die ungeaenderte Kachel zeigt weiterhin den vollen Umriss")
				.isGreaterThan(4);

		List<MvtTileDecoder.Feature> heatmapFeatures = MvtTileDecoder.decode(heatmap).get(0).features();
		assertThat(heatmapFeatures).hasSize(1);
		MvtTileDecoder.Feature heatmapFeature = heatmapFeatures.get(0);
		assertThat(heatmapFeature.rings()).as("genau ein Punkt statt des Umrisses").hasSize(1);
		assertThat(heatmapFeature.rings().get(0)).hasSize(1);
		assertThat(heatmapFeature.properties()).containsEntry("wert", 7L);

		// The decisive check: not just "one point", but the *right* point. Renders
		// ST_Centroid and ST_PointOnSurface themselves as an ordinary point layer -- so
		// MvtService's own, already-proven point encoding is the oracle for where each
		// one lands in tile-local coordinates -- and compares the heatmap's single point
		// against both. Without this, the mutation ST_PointOnSurface -> ST_Centroid in
		// heatmapGeometryExpression passed every test in this class (proven while writing
		// this one): "exactly one point" alone cannot tell the two functions apart.
		String referenceTable = createTable("geom geometry(MultiPoint, 25832) NOT NULL, kind text");
		jdbc.sql(("INSERT INTO %s (geom, kind) VALUES "
				+ "(ST_Multi(ST_Centroid(ST_Transform(ST_SetSRID(ST_GeomFromText(:wkt), 4326), 25832))), 'centroid'),"
				+ "(ST_Multi(ST_PointOnSurface(ST_Transform(ST_SetSRID(ST_GeomFromText(:wkt), 4326), 25832))), 'pos')")
				.formatted(SqlIdentifier.quoteLayerTable(referenceTable)))
				.param("wkt", uShapeWkt).update();
		byte[] reference = mvtService.renderTile(referenceTable, 25832, List.of("kind"), List.of(),
				GeometryType.MULTIPOINT, false, tile[0], tile[1], tile[2]).mvt();
		List<MvtTileDecoder.Feature> referenceFeatures = MvtTileDecoder.decode(reference).get(0).features();
		List<long[]> centroidRing = referenceFeatures.stream()
				.filter(f -> "centroid".equals(f.properties().get("kind"))).findFirst().orElseThrow().rings().get(0);
		List<long[]> pointOnSurfaceRing = referenceFeatures.stream()
				.filter(f -> "pos".equals(f.properties().get("kind"))).findFirst().orElseThrow().rings().get(0);

		assertThat(heatmapFeature.rings().get(0))
				.as("die Heatmap-Koordinate muss mit ST_PointOnSurface uebereinstimmen")
				.usingRecursiveComparison().isEqualTo(pointOnSurfaceRing);
		assertThat(heatmapFeature.rings().get(0))
				.as("und darf nicht zufaellig mit ST_Centroid uebereinstimmen -- das waere die falsche Funktion")
				.usingRecursiveComparison().isNotEqualTo(centroidRing);
	}

	// --- MULTILINESTRING: evenly spaced points, weight undivided -----------------------

	@Test
	@DisplayName("a line shorter than the point spacing still gets exactly one point, not zero")
	void aShortLineStillGetsOnePoint() {
		String tableName = createTable("geom geometry(MultiLineString, 25832) NOT NULL, wert integer");
		int[] tile = tileAt(12);
		double[] bounds = tileLonLatBounds(tile[0], tile[1], tile[2]);
		double lon0 = lerp(bounds[0], bounds[2], 0.50);
		double lon1 = lerp(bounds[0], bounds[2], 0.501); // a sliver, far under the z=12 spacing
		double lat = (bounds[1] + bounds[3]) / 2;

		insertLine(tableName, lon0, lat, lon1, lat, "wert", 5);

		byte[] mvt = mvtService.renderTile(tableName, 25832, List.of("wert"), List.of(),
				GeometryType.MULTILINESTRING, true, tile[0], tile[1], tile[2]).mvt();

		List<MvtTileDecoder.Feature> features = MvtTileDecoder.decode(mvt).get(0).features();
		assertThat(features).hasSize(1);
		assertThat(features.get(0).properties()).containsEntry("wert", 5L);
	}

	/**
	 * The report's point-count measurement, at all three zoom levels the contract asks
	 * for: one line spanning (almost) the full width of its tile, at z=8, z=12 and z=16,
	 * around Hamburg's latitude (53.55°N, {@link #CENTER_LAT}) -- this project's actual
	 * domain, not the equator.
	 *
	 * <p>Measured: <strong>18 points at z=8, 18 at z=12, 18 at z=16</strong> -- flat
	 * across every zoom, which is the point of deriving the spacing from the tile's own
	 * ground width rather than a fixed metre figure (see {@code
	 * MvtService#heatmapPointSpacingMetres}). 18, not {@code HEATMAP_POINTS_ACROSS_TILE}
	 * (32, {@code MvtService}), because that constant is calibrated against the
	 * <em>equatorial</em> tile width on purpose (see that field's own javadoc) while this
	 * line runs through 53.55°N, where a Web Mercator tile's real ground width is only
	 * {@code cos(53.55°) ≈ 0.594} of the equatorial figure -- 32 * 0.594 * 0.98 (the
	 * 1%..99% margin below) ≈ 18.6, {@code floor}ed by {@code ST_LineInterpolatePoints}
	 * to 18. That is the safe direction: fewer points than the equatorial target, never
	 * more, so the simplification never risks the "millions of points" failure mode --
	 * only a slightly coarser heatmap towards the poles.
	 *
	 * <p>Every one of them sits far under {@code DEFAULT_MAX_FEATURES_PER_TILE} (50.000):
	 * at 18 rows per full-width line, roughly 2.700 such lines would have to cross the
	 * same tile before that limit -- calibrated for roughly 19-byte point rows, see that
	 * constant's own note -- came anywhere near truncating it.
	 */
	@Test
	@DisplayName("a full-tile-width line carries about 18 points at z=8, z=12 and z=16 alike, near Hamburg's latitude")
	void heatmapPointSpacingScalesWithZoom() {
		for (int z : new int[] { 8, 12, 16 }) {
			String tableName = createTable("geom geometry(MultiLineString, 25832) NOT NULL, wert integer");
			int[] tile = tileAt(z);
			double[] bounds = tileLonLatBounds(tile[0], tile[1], tile[2]);
			double lat = (bounds[1] + bounds[3]) / 2;
			// 1%..99% of the tile's own width: just inside the envelope on both sides, so
			// the line is not lost to floating-point rounding at the tile boundary itself.
			insertLine(tableName, lerp(bounds[0], bounds[2], 0.01), lat,
					lerp(bounds[0], bounds[2], 0.99), lat, "wert", 11);

			byte[] mvt = mvtService.renderTile(tableName, 25832, List.of("wert"), List.of(),
					GeometryType.MULTILINESTRING, true, tile[0], tile[1], tile[2]).mvt();
			List<MvtTileDecoder.Feature> features = MvtTileDecoder.decode(mvt).get(0).features();

			// 18 measured at every one of the three zoom levels (see the method javadoc for
			// the maths); a few points of slack for ST_Segmentize/ST_Transform's own distortion.
			assertThat(features.size())
					.as("z=%d: Punkte je Kachel bei einer Linie ueber die volle Kachelbreite", z)
					.isBetween(14, 24);
			assertThat(50_000 / features.size())
					.as("z=%d: so viele Linien dieser Laenge muessten sich eine Kachel teilen, "
							+ "bevor die Kuerzungsgrenze greift", z)
					.isGreaterThan(1_000);
			// Every point still carries the line's own, undivided weight (this package's
			// decision, documented on MvtService#interpolatedLinePoints and in the report).
			assertThat(features).allSatisfy(feature -> assertThat(feature.properties()).containsEntry("wert", 11L));
		}
	}

	/**
	 * The weight-splitting decision (CONTRACT.md), pinned down directly: a line broken
	 * into several points keeps its full attribute value on <em>every</em> point rather
	 * than dividing it by the point count. See {@code MvtService#interpolatedLinePoints}
	 * and the report for why.
	 */
	@Test
	@DisplayName("every point of a decomposed line carries the line's full weight, not weight/pointCount")
	void weightIsNotDividedAcrossGeneratedPoints() {
		String tableName = createTable("geom geometry(MultiLineString, 25832) NOT NULL, wert integer");
		int[] tile = tileAt(14);
		double[] bounds = tileLonLatBounds(tile[0], tile[1], tile[2]);
		double lat = (bounds[1] + bounds[3]) / 2;
		insertLine(tableName, lerp(bounds[0], bounds[2], 0.05), lat,
				lerp(bounds[0], bounds[2], 0.95), lat, "wert", 900);

		byte[] mvt = mvtService.renderTile(tableName, 25832, List.of("wert"), List.of(),
				GeometryType.MULTILINESTRING, true, tile[0], tile[1], tile[2]).mvt();
		List<MvtTileDecoder.Feature> features = MvtTileDecoder.decode(mvt).get(0).features();

		assertThat(features).hasSizeGreaterThan(1);
		assertThat(features).allSatisfy(feature -> assertThat(feature.properties()).containsEntry("wert", 900L));
	}

	/**
	 * A line crossing the tile boundary twice, so the clip-then-dump step in
	 * {@code MvtService#interpolatedLinePoints} really does hand two separate pieces to
	 * {@code ST_LineInterpolatePoints} rather than one -- the "several disjoint pieces"
	 * half of the robustness this package's SQL was tested against directly (the other
	 * half, a line only grazing the tile edge, was proven against a real PostGIS during
	 * development and is documented on that method; engineering an exact tangency through
	 * the full tile-envelope transform in a JUnit test is impractical).
	 */
	@Test
	@DisplayName("a line leaving and re-entering the tile still renders, in two separately spaced pieces")
	void aLineCrossingTheTileBoundaryTwiceStillRenders() {
		String tableName = createTable("geom geometry(MultiLineString, 25832) NOT NULL, wert integer");
		int[] tile = tileAt(11);
		double[] bounds = tileLonLatBounds(tile[0], tile[1], tile[2]);
		double lat = (bounds[1] + bounds[3]) / 2;
		double width = bounds[2] - bounds[0];

		// Well outside on the west, a dip inside, well outside on the east: two disjoint
		// pieces of the intersection, not one.
		String lineWkt = String.format(java.util.Locale.ROOT, "LINESTRING(%f %f, %f %f, %f %f)",
				bounds[0] - width, lat, (bounds[0] + bounds[2]) / 2, lat, bounds[2] + width, lat);
		jdbc.sql(("INSERT INTO %s (geom, wert) VALUES "
				+ "(ST_Multi(ST_Transform(ST_SetSRID(ST_GeomFromText(:wkt), 4326), 25832)), :wert)")
				.formatted(SqlIdentifier.quoteLayerTable(tableName)))
				.param("wkt", lineWkt).param("wert", 3).update();

		byte[] mvt = mvtService.renderTile(tableName, 25832, List.of("wert"), List.of(),
				GeometryType.MULTILINESTRING, true, tile[0], tile[1], tile[2]).mvt();

		assertThat(mvt).as("darf nicht mit einem SQL-Fehler scheitern").isNotNull();
		List<MvtTileDecoder.Feature> features = MvtTileDecoder.decode(mvt).get(0).features();
		assertThat(features).isNotEmpty();
		assertThat(features).allSatisfy(feature -> assertThat(feature.properties()).containsEntry("wert", 3L));
	}

	// --- an unweighted heatmap -- every point counts equally ---------------------------

	@Test
	@DisplayName("a heatmap style without a weight field carries no attribute at all")
	void unweightedHeatmapCarriesNoAttribute() {
		String tableName = createTable("geom geometry(MultiPolygon, 25832) NOT NULL, wert integer");
		int[] tile = tileAt(9);
		double[] bounds = tileLonLatBounds(tile[0], tile[1], tile[2]);
		insertSquare(tableName, bounds, "wert", 3);

		// No attribute columns requested at all -- exactly what LayerStyleService.tileColumns
		// resolves to for a heatmap without a field (LayerStyleTest#acceptsAHeatmapRendererWithoutAField).
		byte[] mvt = mvtService.renderTile(tableName, 25832, List.of(), List.of(),
				GeometryType.MULTIPOLYGON, true, tile[0], tile[1], tile[2]).mvt();

		MvtTileDecoder.Layer decoded = MvtTileDecoder.decode(mvt).get(0);
		assertThat(decoded.keys()).isEmpty();
		assertThat(decoded.features()).hasSize(1);
	}

	// --- helpers -------------------------------------------------------------------

	private String createTable(String columns) {
		String tableName = SqlIdentifier.tableName(UUID.randomUUID());
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("CREATE TABLE %s (fid bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, %s)"
				.formatted(table, columns)).update();
		jdbc.sql("CREATE INDEX ON %s USING GIST (geom)".formatted(table)).update();
		tablesToDrop.add(tableName);
		return tableName;
	}

	private void insert(String tableName, String geomExpr, String weightColumn, int weight,
			double lon, double lat) {
		jdbc.sql(("INSERT INTO %s (geom, " + weightColumn + ") VALUES (" + geomExpr + ", :wert)")
				.formatted(SqlIdentifier.quoteLayerTable(tableName)))
				.param("lon", lon).param("lat", lat).param("wert", weight)
				.update();
	}

	private void insertLine(String tableName, double lon0, double lat0, double lon1, double lat1,
			String weightColumn, int weight) {
		jdbc.sql(("INSERT INTO %s (geom, " + weightColumn + ") VALUES "
				+ "(ST_Multi(ST_Transform(ST_SetSRID(ST_MakeLine(ST_MakePoint(:lon0, :lat0), "
				+ "ST_MakePoint(:lon1, :lat1)), 4326), 25832)), :wert)")
				.formatted(SqlIdentifier.quoteLayerTable(tableName)))
				.param("lon0", lon0).param("lat0", lat0).param("lon1", lon1).param("lat1", lat1)
				.param("wert", weight)
				.update();
	}

	private void insertSquare(String tableName, double[] bounds, String weightColumn, int weight) {
		double lonMin = lerp(bounds[0], bounds[2], 0.3);
		double lonMax = lerp(bounds[0], bounds[2], 0.7);
		double latMin = lerp(bounds[1], bounds[3], 0.3);
		double latMax = lerp(bounds[1], bounds[3], 0.7);
		jdbc.sql(("INSERT INTO %s (geom, " + weightColumn + ") VALUES "
				+ "(ST_Multi(ST_Transform(ST_SetSRID(ST_MakeEnvelope(:lonMin, :latMin, :lonMax, :latMax), 4326), "
				+ "25832)), :wert)")
				.formatted(SqlIdentifier.quoteLayerTable(tableName)))
				.param("lonMin", lonMin).param("latMin", latMin).param("lonMax", lonMax).param("latMax", latMax)
				.param("wert", weight)
				.update();
	}

	/** {@code z, x, y} in that order -- matching {@code MvtService.renderTile}'s parameter order. */
	private static int[] tileAt(int z) {
		long n = 1L << z;
		int x = (int) Math.floor((CENTER_LON + 180.0) / 360.0 * n);
		double latRad = Math.toRadians(CENTER_LAT);
		int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
		return new int[] { z, x, y };
	}

	/** {@code lonMin, latMin, lonMax, latMax}, mirroring {@code MvtService#tileFootprint}. */
	private static double[] tileLonLatBounds(int z, int x, int y) {
		double n = Math.scalb(1.0, z);
		return new double[] {
				x / n * 360.0 - 180.0,
				tileLat(y + 1, n),
				(x + 1) / n * 360.0 - 180.0,
				tileLat(y, n) };
	}

	private static double tileLat(double row, double n) {
		return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * row / n))));
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}
}
