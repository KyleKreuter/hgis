package de.kreuter.hgis.tiles;

import de.kreuter.hgis.common.SqlIdentifier;
import java.util.Collection;
import java.util.stream.Collectors;
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
 *
 * Beyond {@code fid} a tile carries only the attributes the layer's style classifies or
 * labels by. That is what lets MapLibre colour features itself with {@code match} or
 * {@code step} instead of asking the server for one map layer per category -- and
 * keeping it to those attributes is what keeps a tile small: everything else a client
 * wants about a feature comes from the feature API, which has no tile budget to spend.
 *
 * A layer can also be rendered clipped to a mask layer's polygons, either to what lies
 * inside them or to what lies outside (CONTRACT.md phase 19/20). Whether that applies --
 * to which table, and in which mode -- is entirely the caller's decision; this class only
 * ever cuts against the mask table and mode it is given, it never looks at z-index or
 * which layer is marked as a project's mask.
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
			  SELECT l.fid,%s
			         ST_AsMVTGeom(ST_Transform(l.geom, 3857), b.merc, 4096, 64, true) AS geom
			  FROM %s l, bounds b
			  WHERE l.geom && b.native
			) AS tile
			WHERE tile.geom IS NOT NULL
			""";

	/**
	 * Same shape as {@link #TILE_QUERY}, plus a clip mask in {@code inside} mode: every
	 * feature is intersected with the union of the mask's polygons touching the tile
	 * before encoding, so a feature straddling the mask edge is cut, not just kept or
	 * dropped whole.
	 *
	 * <p>{@code ST_Union} is not optional. Without it, a feature crossing two mask
	 * polygons would join twice and reach {@code ST_AsMVT} as two overlapping rows for
	 * the same {@code fid} -- invisible on an opaque fill, but visibly darker wherever
	 * semi-transparent fills overlap.
	 *
	 * <p>The mask is unioned only within the tile ({@code m.geom && b.native}), never
	 * across the whole table: a mask layer can hold thousands of polygons, and the tile
	 * envelope is what keeps this cheap.
	 *
	 * <p>{@code l.geom && b.native} still runs against the raw column, exactly as in
	 * {@link #TILE_QUERY} -- the intersection lives only in the SELECT list, never in
	 * the layer table's predicate, so the GiST index on {@code geom} still applies. The
	 * mask is compared to the tile envelope in the layer's own SRID, no transform: every
	 * layer of a project already shares one SRID, so mask and layer are directly
	 * comparable.
	 *
	 * <p>{@code l.geom && mask.geom} additionally keeps only features that actually touch
	 * the mask -- correct here, since a feature that never touches the mask can have
	 * nothing inside it to keep. CONTRACT.md phase 20 lays out why that same predicate
	 * would be exactly backwards for {@link #OUTSIDE_CLIPPED_TILE_QUERY}.
	 */
	private static final String INSIDE_CLIPPED_TILE_QUERY = """
			WITH bounds AS (
			  SELECT ST_TileEnvelope(:z, :x, :y) AS merc,
			         ST_Transform(ST_TileEnvelope(:z, :x, :y), :srid) AS native
			),
			mask AS (
			  SELECT ST_Union(m.geom) AS geom
			  FROM %s m, bounds b
			  WHERE m.geom && b.native
			)
			SELECT ST_AsMVT(tile, 'layer', 4096, 'geom', 'fid')
			FROM (
			  SELECT l.fid,%s
			         ST_AsMVTGeom(ST_Transform(ST_Intersection(l.geom, mask.geom), 3857),
			                      b.merc, 4096, 64, true) AS geom
			  FROM %s l, bounds b, mask
			  WHERE l.geom && b.native AND l.geom && mask.geom
			) AS tile
			WHERE tile.geom IS NOT NULL
			""";

	/**
	 * Same shape as {@link #INSIDE_CLIPPED_TILE_QUERY}, but keeps what lies outside the
	 * mask instead of inside it (CONTRACT.md phase 20). Two changes make that correct,
	 * and both are easy to get backwards:
	 *
	 * <ol>
	 *   <li>No {@code l.geom && mask.geom} predicate. That predicate keeps only features
	 *       touching the mask -- right for {@code inside}, where nothing outside the mask
	 *       could keep anything, but exactly wrong here: it would keep only the features
	 *       this mode is supposed to cut away, and drop everything meant to survive
	 *       untouched.</li>
	 *   <li>A {@code CASE} around {@code ST_Difference}. {@code mask.geom} is {@code NULL}
	 *       whenever the mask has no polygon touching this tile -- {@code ST_Union} over
	 *       zero rows -- and {@code ST_Difference(geom, NULL)} is itself {@code NULL}.
	 *       Left unguarded, every tile the mask never reaches would render as empty, which
	 *       is backwards for {@code outside}: such a tile has to show its layer whole. For
	 *       {@code inside} the same {@code NULL} is correct as is, which is why
	 *       {@link #INSIDE_CLIPPED_TILE_QUERY} carries no such guard.</li>
	 * </ol>
	 */
	private static final String OUTSIDE_CLIPPED_TILE_QUERY = """
			WITH bounds AS (
			  SELECT ST_TileEnvelope(:z, :x, :y) AS merc,
			         ST_Transform(ST_TileEnvelope(:z, :x, :y), :srid) AS native
			),
			mask AS (
			  SELECT ST_Union(m.geom) AS geom
			  FROM %s m, bounds b
			  WHERE m.geom && b.native
			)
			SELECT ST_AsMVT(tile, 'layer', 4096, 'geom', 'fid')
			FROM (
			  SELECT l.fid,%s
			         ST_AsMVTGeom(ST_Transform(
			           CASE WHEN mask.geom IS NULL THEN l.geom
			                ELSE ST_Difference(l.geom, mask.geom) END, 3857),
			           b.merc, 4096, 64, true) AS geom
			  FROM %s l, bounds b, mask
			  WHERE l.geom && b.native
			) AS tile
			WHERE tile.geom IS NOT NULL
			""";

	/** The one clip mode besides {@code "inside"} -- see CONTRACT.md phase 20. */
	private static final String CLIP_MODE_OUTSIDE = "outside";

	private final JdbcClient jdbc;

	MvtService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Renders one tile. Returns {@code null} when the tile has no features -- callers
	 * turn that into a 204, never an empty byte array with a 200.
	 *
	 * @param attributeColumns column names to carry as tile properties, resolved from the
	 *                         layer's style through {@code layer_field}; never a name that
	 *                         came out of the style document itself
	 * @param maskTableName    the clip mask's table, or {@code null} to render unclipped
	 *                         -- exactly the query this method ran before CONTRACT.md
	 *                         phase 19 introduced clip masks. The caller decides whether
	 *                         a mask applies to this particular layer; passing one here
	 *                         always clips, regardless of z-index.
	 * @param clipMode         {@code "inside"} or {@code "outside"} (CONTRACT.md phase
	 *                         20); ignored when {@code maskTableName} is {@code null}.
	 *                         Anything other than {@code "outside"} renders {@code inside},
	 *                         matching the column's own default before phase 20 existed.
	 */
	public byte[] renderTile(String tableName, int srid, Collection<String> attributeColumns,
			String maskTableName, String clipMode, int z, int x, int y) {
		byte[] mvt = jdbc.sql(query(tableName, attributeColumns, maskTableName, clipMode))
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
	String explainTile(String tableName, int srid, Collection<String> attributeColumns,
			String maskTableName, String clipMode, int z, int x, int y) {
		String sql = "EXPLAIN (ANALYZE, FORMAT JSON) " + query(tableName, attributeColumns, maskTableName, clipMode);
		return jdbc.sql(sql)
				.param("z", z)
				.param("x", x)
				.param("y", y)
				.param("srid", srid)
				.query(String.class)
				.single();
	}

	private String query(String tableName, Collection<String> attributeColumns, String maskTableName,
			String clipMode) {
		String attributes = selectedAttributes(attributeColumns);
		String layerTable = SqlIdentifier.quoteLayerTable(tableName);
		if (maskTableName == null) {
			return TILE_QUERY.formatted(attributes, layerTable);
		}
		String maskTable = SqlIdentifier.quoteLayerTable(maskTableName);
		if (CLIP_MODE_OUTSIDE.equals(clipMode)) {
			return OUTSIDE_CLIPPED_TILE_QUERY.formatted(maskTable, attributes, layerTable);
		}
		return INSIDE_CLIPPED_TILE_QUERY.formatted(maskTable, attributes, layerTable);
	}

	/**
	 * The attribute part of the inner SELECT, empty for an unstyled layer.
	 *
	 * <p>Everything {@code ST_AsMVT} finds beside the geometry and the id column becomes a
	 * tile property, keyed by the column name -- the same key the feature API uses for its
	 * properties, and the only one {@link SqlIdentifier} will let into SQL at all.
	 */
	private static String selectedAttributes(Collection<String> attributeColumns) {
		if (attributeColumns == null || attributeColumns.isEmpty()) {
			return "";
		}
		return attributeColumns.stream()
				.map(column -> "l." + SqlIdentifier.quoteColumn(column))
				.collect(Collectors.joining(", ", " ", ","));
	}
}
