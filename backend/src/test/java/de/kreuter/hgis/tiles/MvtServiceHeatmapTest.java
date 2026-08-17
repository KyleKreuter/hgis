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
	 * for -- corrected after review: the first version of this test scaled the line's own
	 * length to ~98% of each zoom's tile width, which made the point count a tautology of
	 * the spacing formula itself ({@code spacing = tileWidth(z) / 32}, so a
	 * {@code tileWidth(z)}-sized line always divides into ~32 points, at any {@code z}, by
	 * construction -- the test proved nothing about the real behaviour of a real, fixed
	 * line). This version fixes the line's length at exactly 2000 native metres,
	 * independent of {@code z}, near Hamburg's latitude ({@link #CENTER_LAT}).
	 *
	 * <p>Measured: <strong>1 point at z=8, 6 at z=12, 19 at z=16</strong> -- three different
	 * regimes, not one straight line of growth:
	 *
	 * <ul>
	 *   <li>z=8: the tile is enormous (real ground width, at this latitude, roughly 93 km) --
	 *       the 2000 m line sits far inside it, entirely unclipped, but is itself shorter
	 *       than one spacing unit (about 2900 m here), so it gets exactly one point, the
	 *       same "too short for even one grid step" floor {@link
	 *       #aShortLineStillGetsOnePoint} checks directly.</li>
	 *   <li>z=12: still unclipped (tile width here is roughly 5,8 km, comfortably over
	 *       2000 m) -- this is the one regime where the point count is pure {@code
	 *       length / spacing}, and it is where the real, non-tautological growth shows: 6
	 *       points, {@code floor(2000 / 306.8m)}.</li>
	 *   <li>z=16: the tile's real ground width has shrunk to roughly 363 m -- <em>less</em>
	 *       than the 2000 m line -- so the tile now shows only the clipped fragment that
	 *       fits inside it, at the same per-tile-width density any full-width line gets at
	 *       this zoom (matching the roughly 18 points a full-tile-width line measured at
	 *       z=16 in an earlier version of this class). Zooming in far enough turns "a fixed
	 *       line" and "a full-tile-width line" into the same question.</li>
	 * </ul>
	 *
	 * <p>The line's <em>total</em>, undivided weight grows the same way (100 at z=8, 600 at
	 * z=12, 1.900 at z=16 -- a 19x span end to end), which sounds alarming until the actual
	 * invariant this package holds is named correctly:
	 *
	 * <p><strong>The right invariant is density per screen pixel, not total real-world
	 * mass.</strong> A tile always renders at a roughly fixed number of CSS pixels wide
	 * (512, conventionally), and {@code spacing = tileWidth(z) / HEATMAP_POINTS_ACROSS_TILE}
	 * (32) means the point spacing in <em>screen</em> pixels is {@code 512 / 32 = 16px} at
	 * every zoom, by construction -- {@code tileWidth(z)} cancels out of that ratio. With an
	 * undivided weight, the amount of "heat" per screen pixel along the line therefore stays
	 * constant across zoom: the same real street looks equally intense at any zoom level,
	 * which is what a screen-space heatmap (MapLibre's {@code heatmap-radius} is itself a
	 * screen-pixel figure, not a world one) should do. Zooming in reveals more of the line on
	 * screen and therefore more points and more total heat -- exactly like zooming into any
	 * other point-sampled rendering reveals more samples -- it does not mean the same stretch
	 * of street becomes "louder". Had the weight instead been divided by the point count
	 * (mass-conserving, keeping the *real-world total* constant instead), the per-screen-pixel
	 * density would have <em>dropped</em> as the point count grew with zoom -- the heatmap
	 * would visibly fade out the closer one zooms into the very place it is meant to
	 * highlight, which is the actually wrong behaviour for this renderer.
	 *
	 * <p>{@link #lineCrossingATileBoundaryProducesTheSameGridOnBothSides} is the other half
	 * of the zoom story: it is not only the point count that must behave sensibly, but the
	 * point <em>positions</em> across a tile boundary.
	 */
	@Test
	@DisplayName("a fixed 2000 m line carries more points at higher zoom, its total weight growing with it")
	void fixedLengthLineGetsMorePointsAtHigherZoom() {
		double[] anchor = nativePoint(CENTER_LON, CENTER_LAT);
		int previousCount = 0;
		for (int z : new int[] { 8, 12, 16 }) {
			String tableName = createTable("geom geometry(MultiLineString, 25832) NOT NULL, wert integer");
			int[] tile = tileAt(z);
			insertLineNative(tableName, anchor[0] - 1000, anchor[1], anchor[0] + 1000, anchor[1], "wert", 100);

			byte[] mvt = mvtService.renderTile(tableName, 25832, List.of("wert"), List.of(),
					GeometryType.MULTILINESTRING, true, tile[0], tile[1], tile[2]).mvt();
			List<MvtTileDecoder.Feature> features = MvtTileDecoder.decode(mvt).get(0).features();

			assertThat(features.size())
					.as("z=%d: eine feste 2000-m-Linie muss bei hoeherem Zoom mehr Punkte tragen", z)
					.isGreaterThan(previousCount);
			previousCount = features.size();
			// Every point still carries the line's own, undivided weight (this package's
			// decision, documented on MvtService#interpolatedLinePoints and in the report).
			assertThat(features).allSatisfy(feature -> assertThat(feature.properties()).containsEntry("wert", 100L));
		}
		assertThat(previousCount)
				.as("bei z=16 muessen mehrere hundert Punkte je Linie noch weit unter der Kuerzungsgrenze bleiben")
				.isLessThan(1_000);
	}

	/**
	 * The seam finding (review after the first version of this package): the first version
	 * of {@code MvtService#interpolatedLinePoints} started every clipped piece's own point
	 * sequence at fraction 0 relative to <em>that piece's own</em> length, so two tiles
	 * clipping the very same line disagreed on where the points fall -- measured on two
	 * real, adjacent tiles at z=12: 158,7 m before the boundary to the last point, a further
	 * 305,7 m (a whole spacing unit) after it to the next, a combined 464,5 m gap, about 1,5x
	 * the ordinary spacing.
	 *
	 * <p>Reproduced here the same way it was found -- two real, adjacent tiles, the last
	 * point of the western one and the first point of the eastern one -- except this
	 * measures the gap in each tile's own local MVT coordinates (0..4096) rather than native
	 * metres: combining both tiles' local x axes into one avoids any unit conversion, and
	 * the comparison against this tile's own <em>ordinary</em> point spacing (measured the
	 * same way, from two points that are not at the boundary) is exactly what tells a
	 * healed seam from the 1,5x gap that found this bug in the first place.
	 *
	 * <p>Measured after the fix: {@code ordinaryGap = 215} local units, {@code boundaryGap =
	 * 216} -- a ratio of 1,005, not 1,5. The realignment in {@code
	 * MvtService#interpolatedLinePoints} (grid measured from each clipped piece's position
	 * along the <em>whole original line</em>, not from its own clipped start) closes the
	 * seam to within MVT's own integer rounding.
	 */
	@Test
	@DisplayName("a line crossing a real tile boundary produces the same point grid on both sides -- no seam gap")
	void lineCrossingATileBoundaryProducesTheSameGridOnBothSides() {
		String tableName = createTable("geom geometry(MultiLineString, 25832) NOT NULL, wert integer");
		int z = 12;
		int[] tileA = tileAt(z);
		int x = tileA[1];
		int y = tileA[2];
		double[] boundsA = tileLonLatBounds(z, x, y);
		double[] boundsB = tileLonLatBounds(z, x + 1, y);
		double lat = (boundsA[1] + boundsA[3]) / 2;

		// Crosses the shared meridian between tile (z,x,y) and (z,x+1,y), well inside each
		// tile on either side -- several points expected on both sides of the boundary.
		insertLine(tableName, lerp(boundsA[0], boundsA[2], 0.2), lat, lerp(boundsB[0], boundsB[2], 0.8), lat,
				"wert", 1);

		List<MvtTileDecoder.Feature> featuresA = MvtTileDecoder.decode(
				mvtService.renderTile(tableName, 25832, List.of(), List.of(),
						GeometryType.MULTILINESTRING, true, z, x, y).mvt())
				.get(0).features();
		List<MvtTileDecoder.Feature> featuresB = MvtTileDecoder.decode(
				mvtService.renderTile(tableName, 25832, List.of(), List.of(),
						GeometryType.MULTILINESTRING, true, z, x + 1, y).mvt())
				.get(0).features();

		List<Long> xsA = featuresA.stream().map(f -> f.rings().get(0).get(0)[0]).sorted().toList();
		List<Long> xsB = featuresB.stream().map(f -> f.rings().get(0).get(0)[0]).sorted().toList();
		assertThat(xsA).as("mindestens zwei Punkte in der westlichen Kachel, um den normalen Abstand zu messen")
				.hasSizeGreaterThanOrEqualTo(2);
		assertThat(xsB).as("mindestens ein Punkt in der oestlichen Kachel").isNotEmpty();

		long ordinaryGap = xsA.get(xsA.size() - 1) - xsA.get(xsA.size() - 2);
		// Both tile-local x axes (each 0..4096, east is increasing x) combined into one:
		// the distance from the last point in A to the tile edge, plus the distance from
		// the tile edge to the first point in B.
		long boundaryGap = (4096 - xsA.get(xsA.size() - 1)) + xsB.get(0);

		assertThat((double) boundaryGap / ordinaryGap)
				.as("die Naht an der Kachelgrenze darf nicht breiter sein als der normale Punktabstand "
						+ "(vorher: etwa das 1,5-fache -- boundaryGap=%d, ordinaryGap=%d)", boundaryGap, ordinaryGap)
				.isBetween(0.8, 1.15);
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
	 * the point generator rather than one -- the "several disjoint pieces" half of this
	 * package's clip robustness. {@link #aLineTouchingTheTileBoundaryOnlyAtAPointStillRenders}
	 * is the other half: a line that only grazes the edge.
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

	/**
	 * A line that touches the tile's own clip boundary tangentially, at exactly one point,
	 * rather than crossing it -- the case that first motivated
	 * {@code ST_CollectionExtract} in {@code MvtService#interpolatedLinePoints}, found
	 * against a real PostGIS during development and reproduced here through the actual tile
	 * pipeline: {@link #nativeBoundsRing} queries the exact boundary
	 * {@code MvtService#nativeBounds} clips against, and the fixture line is built from that
	 * real geometry's own corner, not an approximation of it -- extending the two edges that
	 * meet there past the corner, a standard construction that stays outside a convex
	 * polygon on both sides while touching it at that one point.
	 *
	 * <p>Without {@code ST_CollectionExtract}, {@code ST_Intersection} of this line with the
	 * boundary reduces to a bare {@code POINT} (proven separately, directly in SQL, in the
	 * assertion below), and {@code ST_LineInterpolatePoint} raises "1st arg isn't a line" the
	 * moment that reaches it -- turning the whole tile request into a 500. With it, the tile
	 * renders, empty: a single tangent point carries no length to place a heatmap point
	 * along.
	 */
	@Test
	@DisplayName("a line touching the tile boundary at exactly one point still renders, without an SQL error")
	void aLineTouchingTheTileBoundaryOnlyAtAPointStillRenders() {
		String tableName = createTable("geom geometry(MultiLineString, 25832) NOT NULL, wert integer");
		int[] tile = tileAt(12);
		double[][] ring = parseLineStringWkt(nativeBoundsRing(tile[0], tile[1], tile[2]));
		// A genuine corner: the ring is closed (point 0 == point length-1), so its two
		// immediate neighbours are index 1 and index length-2. ST_Segmentize only ever adds
		// points *along* an edge, never replacing a corner, so index 0 is still one.
		double[] vertex = ring[0];
		double[] previous = ring[ring.length - 2];
		double[] next = ring[1];

		// Extends both edges meeting at the corner past the corner itself: for a convex
		// polygon, a point past either endpoint of an edge lies outside the polygon, since
		// the whole interior sits on one side of that edge's supporting line.
		double p1x = vertex[0] + (vertex[0] - previous[0]) * 2;
		double p1y = vertex[1] + (vertex[1] - previous[1]) * 2;
		double p2x = vertex[0] + (vertex[0] - next[0]) * 2;
		double p2y = vertex[1] + (vertex[1] - next[1]) * 2;

		jdbc.sql(("INSERT INTO %s (geom, wert) VALUES (ST_Multi(ST_SetSRID(ST_MakeLine(ARRAY["
				+ "ST_MakePoint(:p1x, :p1y), ST_MakePoint(:vx, :vy), ST_MakePoint(:p2x, :p2y)]), 25832)), :wert)")
				.formatted(SqlIdentifier.quoteLayerTable(tableName)))
				.param("p1x", p1x).param("p1y", p1y)
				.param("vx", vertex[0]).param("vy", vertex[1])
				.param("p2x", p2x).param("p2y", p2y)
				.param("wert", 1)
				.update();

		String intersectionType = jdbc.sql("SELECT ST_GeometryType(ST_Intersection(l.geom, "
				+ "ST_Transform(ST_Segmentize(ST_TileEnvelope(:z, :x, :y), 100000), 25832))) AS t "
				+ "FROM " + SqlIdentifier.quoteLayerTable(tableName) + " l")
				.param("z", tile[0]).param("x", tile[1]).param("y", tile[2])
				.query(String.class).single();
		assertThat(intersectionType)
				.as("die Testlinie muss den Kachelrand nur tangential in einem Punkt beruehren")
				.isEqualTo("ST_Point");

		byte[] mvt = mvtService.renderTile(tableName, 25832, List.of("wert"), List.of(),
				GeometryType.MULTILINESTRING, true, tile[0], tile[1], tile[2]).mvt();

		assertThat(mvt).as("leere statt fehlschlagende Kachel bei einer nur tangential beruehrenden Linie").isNull();
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

	/** A line given directly in the layer's own native SRID -- no WGS84 roundtrip, exact metres. */
	private void insertLineNative(String tableName, double x0, double y0, double x1, double y1,
			String weightColumn, int weight) {
		jdbc.sql(("INSERT INTO %s (geom, " + weightColumn + ") VALUES "
				+ "(ST_Multi(ST_SetSRID(ST_MakeLine(ST_MakePoint(:x0, :y0), ST_MakePoint(:x1, :y1)), 25832)), :wert)")
				.formatted(SqlIdentifier.quoteLayerTable(tableName)))
				.param("x0", x0).param("y0", y0).param("x1", x1).param("y1", y1).param("wert", weight)
				.update();
	}

	/** The native-SRID coordinate of a WGS84 point, for building geometry with an exact native length. */
	private double[] nativePoint(double lon, double lat) {
		return jdbc.sql("SELECT ST_X(t) AS x, ST_Y(t) AS y FROM "
						+ "(SELECT ST_Transform(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), 25832) AS t) s")
				.param("lon", lon).param("lat", lat)
				.query((rs, rowNum) -> new double[] { rs.getDouble("x"), rs.getDouble("y") })
				.single();
	}

	/**
	 * The native-CRS boundary polygon a tile's own query clips against -- the exact
	 * expression {@code MvtService#nativeBounds} builds for a tile within
	 * {@code ProjectionDomain}'s coverage. Queried directly (not reimplemented) so a test
	 * that needs to touch this boundary on purpose -- see
	 * {@link #aLineTouchingTheTileBoundaryOnlyAtAPointStillRenders} -- works from the same
	 * geometry the production query actually clips against, not an approximation of it.
	 */
	private String nativeBoundsRing(int z, int x, int y) {
		return jdbc.sql("SELECT ST_AsText(ST_ExteriorRing(ST_Transform("
						+ "ST_Segmentize(ST_TileEnvelope(:z, :x, :y), 100000), 25832))) AS ring")
				.param("z", z).param("x", x).param("y", y)
				.query(String.class).single();
	}

	/** {@code x, y} pairs of a WKT {@code LINESTRING(...)}, in ring order. */
	private static double[][] parseLineStringWkt(String wkt) {
		String inner = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')'));
		String[] pairs = inner.split(",");
		double[][] points = new double[pairs.length][2];
		for (int i = 0; i < pairs.length; i++) {
			String[] xy = pairs[i].trim().split("\\s+");
			points[i][0] = Double.parseDouble(xy[0]);
			points[i][1] = Double.parseDouble(xy[1]);
		}
		return points;
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
