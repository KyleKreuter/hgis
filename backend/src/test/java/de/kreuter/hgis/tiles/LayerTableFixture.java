package de.kreuter.hgis.tiles;

import de.kreuter.hgis.common.SqlIdentifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds a physical layer table exactly as it will later be created by the import
 * chain: {@code layer_<hex>} in {@code gis_data}, a {@code geometry(MultiPolygon, 25832)}
 * column and a GiST index on it -- Spur B (import) does not exist yet, so tests have
 * to stand this up themselves.
 *
 * A handful of "signal" features sit tightly clustered around one native-CRS point;
 * several thousand "noise" features are scattered far away, in a disjoint bounding
 * box, so a tile request over the signal cluster can never accidentally pick up noise
 * -- and so the noise is large enough to make a sequential scan show up unmistakably
 * in an EXPLAIN if the tile query ever regressed to one.
 *
 * The table carries two attribute columns besides the geometry. Not because the tile
 * needs them, but because it must not carry them unless a style asks for it: with a
 * single column there would be no way to tell "the right attribute" from "every
 * attribute".
 */
final class LayerTableFixture {

	/** Native point the signal cluster sits around: inside German UTM32N eastings/northings, deliberately outside the noise bounding box below. */
	private static final double SIGNAL_X = 850_000;
	private static final double SIGNAL_Y = 6_050_000;

	private static final int NOISE_FEATURE_COUNT = 5_000;

	/** A text attribute a categorized renderer could classify by, and an integer one for graduated. */
	static final String CATEGORY_COLUMN = "nutzungsart";
	static final String NUMERIC_COLUMN = "einwohner";

	/**
	 * @param categories fid to its {@link #CATEGORY_COLUMN} value, for the signal features only
	 */
	record TestLayer(String tableName, int zoom, int tileX, int tileY, List<Long> featureIds,
			Map<Long, String> categories) {
	}

	private LayerTableFixture() {
	}

	static TestLayer create(JdbcClient jdbc, int signalFeatureCount) {
		String tableName = SqlIdentifier.tableName(UUID.randomUUID());
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    nutzungsart text,
				    einwohner   integer,
				    geom        geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX ON %s USING GIST (geom)".formatted(table)).update();

		int zoom = 10;
		int[] tile = tileForNativePoint(jdbc, SIGNAL_X, SIGNAL_Y, zoom);

		List<Long> featureIds = new ArrayList<>();
		Map<Long, String> categories = new LinkedHashMap<>();
		for (int i = 0; i < signalFeatureCount; i++) {
			double x0 = SIGNAL_X + i * 20;
			double y0 = SIGNAL_Y + i * 20;
			String category = i % 2 == 0 ? "Wohnen" : "Gewerbe";
			Long id = jdbc.sql("""
					INSERT INTO %s (geom, nutzungsart, einwohner)
					VALUES (ST_Multi(ST_MakeEnvelope(:x0, :y0, :x0 + 10, :y0 + 10, 25832)),
					        :category, :einwohner)
					RETURNING fid
					""".formatted(table))
					.param("x0", x0)
					.param("y0", y0)
					.param("category", category)
					.param("einwohner", i * 100)
					.query(Long.class)
					.single();
			featureIds.add(id);
			categories.put(id, category);
		}

		// Scattered across a sub-region of Germany that never overlaps SIGNAL_X/SIGNAL_Y,
		// generated entirely server-side -- fast, and no Java round trip per row needed.
		jdbc.sql("""
				INSERT INTO %s (geom, nutzungsart, einwohner)
				SELECT ST_Multi(ST_Buffer(
				    ST_SetSRID(ST_MakePoint(280000 + random() * 320000, 5230000 + random() * 670000), 25832),
				    25)),
				    'Wald', NULL
				FROM generate_series(1, :count)
				""".formatted(table))
				.param("count", NOISE_FEATURE_COUNT)
				.update();

		jdbc.sql("ANALYZE " + table).update();

		return new TestLayer(tableName, zoom, tile[0], tile[1], featureIds, categories);
	}

	/**
	 * Derives the WGS84 lng/lat of a native-CRS point via PostGIS itself -- the same
	 * transform the production query relies on -- and turns it into the XYZ tile index
	 * ST_TileEnvelope uses, so the tile requested in a test is always computed from the
	 * actual data, never guessed.
	 */
	private static int[] tileForNativePoint(JdbcClient jdbc, double nativeX, double nativeY, int zoom) {
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
