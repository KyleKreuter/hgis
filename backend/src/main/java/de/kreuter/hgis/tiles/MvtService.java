package de.kreuter.hgis.tiles;

import de.kreuter.hgis.common.SqlIdentifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Renders a single vector tile for a layer. The whole encoding happens inside
 * PostGIS via {@code ST_AsMVT} -- the protobuf bytes come straight out of the
 * database, no MVT encoding library is needed on the Java side.
 *
 * The tile envelope is transformed into the layer's storage CRS, never the other way
 * around: the {@code WHERE} clause compares the raw, untransformed {@code geom}
 * column against a bound value, exactly what the GiST index on {@code geom} was built
 * for. Transforming {@code geom} itself in the predicate would make that index
 * unusable and turn every tile request into a sequential scan over the whole layer
 * table.
 */
@Service
public class MvtService {

	private static final String TILE_QUERY = """
			WITH bounds AS (
			  SELECT ST_TileEnvelope(:z, :x, :y) AS merc,
			         ST_Transform(ST_TileEnvelope(:z, :x, :y), :srid) AS native
			)
			SELECT ST_AsMVT(tile, 'layer', 4096, 'geom', 'fid')
			FROM (
			  SELECT l.fid,
			         ST_AsMVTGeom(ST_Transform(l.geom, 3857), b.merc, 4096, 64, true) AS geom
			  FROM %s l, bounds b
			  WHERE l.geom && b.native
			) AS tile
			WHERE tile.geom IS NOT NULL
			""";

	private final JdbcClient jdbc;

	MvtService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Renders one tile. Returns {@code null} when the tile has no features -- callers
	 * turn that into a 204, never an empty byte array with a 200.
	 */
	public byte[] renderTile(String tableName, int srid, int z, int x, int y) {
		byte[] mvt = jdbc.sql(query(tableName))
				.param("z", z)
				.param("x", x)
				.param("y", y)
				.param("srid", srid)
				.query(byte[].class)
				.single();
		return (mvt == null || mvt.length == 0) ? null : mvt;
	}

	/**
	 * The query plan for exactly the query {@link #renderTile} runs, as the JSON
	 * produced by {@code EXPLAIN (ANALYZE, FORMAT JSON)}. Exists solely so tests can
	 * prove the predicate stays index-friendly; never called at runtime.
	 */
	String explainTile(String tableName, int srid, int z, int x, int y) {
		String sql = "EXPLAIN (ANALYZE, FORMAT JSON) " + query(tableName);
		return jdbc.sql(sql)
				.param("z", z)
				.param("x", x)
				.param("y", y)
				.param("srid", srid)
				.query(String.class)
				.single();
	}

	private String query(String tableName) {
		return TILE_QUERY.formatted(SqlIdentifier.quoteLayerTable(tableName));
	}
}
