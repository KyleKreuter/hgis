package de.kreuter.hgis.tiles;

import de.kreuter.hgis.common.SqlIdentifier;
import java.util.Collection;
import java.util.List;
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
 * A layer can also be rendered clipped to any number of mask layers' polygons, each in
 * one of four modes (CONTRACT.md phase 21). Whether a mask applies -- to which table,
 * and in which mode -- is entirely the caller's decision; this class only ever cuts
 * against the masks it is given, it never looks at z-index or which layers are marked
 * as a project's masks.
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

	/** The four clip modes {@code layer.clip_mode} may carry (CONTRACT.md phase 21). */
	private static final String MODE_INSIDE_WHOLE = "insideWhole";
	private static final String MODE_INSIDE_CLIPPED = "insideClipped";
	private static final String MODE_OUTSIDE_WHOLE = "outsideWhole";
	private static final String MODE_OUTSIDE_CLIPPED = "outsideClipped";

	/** One mask acting on a layer's tile: the table to cut against, and how. */
	public record ClipMask(String tableName, String mode) {}

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
	 * @param masks            the masks acting on this layer, unterste zuerst, or empty
	 *                         to render unclipped -- never {@code null}. An empty list
	 *                         produces exactly the query this method ran before
	 *                         CONTRACT.md phase 19 introduced clip masks at all. The
	 *                         caller decides which masks apply to this particular layer;
	 *                         passing one here always clips, regardless of z-index.
	 */
	public byte[] renderTile(String tableName, int srid, Collection<String> attributeColumns,
			List<ClipMask> masks, int z, int x, int y) {
		byte[] mvt = jdbc.sql(query(tableName, attributeColumns, masks))
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
			List<ClipMask> masks, int z, int x, int y) {
		String sql = "EXPLAIN (ANALYZE, FORMAT JSON) " + query(tableName, attributeColumns, masks);
		return jdbc.sql(sql)
				.param("z", z)
				.param("x", x)
				.param("y", y)
				.param("srid", srid)
				.query(String.class)
				.single();
	}

	/**
	 * Builds the query for {@code masks}. Unclipped when {@code masks} is empty, exactly
	 * {@link #TILE_QUERY}. Otherwise builds it from parts (CONTRACT.md phase 21):
	 *
	 * <ul>
	 *   <li>The two {@code *Whole} modes filter, they do not cut geometry. Each becomes
	 *       an {@code EXISTS}/{@code NOT EXISTS} subquery against the mask's whole table
	 *       -- never a tile-bounded union -- so a feature the mask reaches anywhere is
	 *       kept or dropped as a whole, not split across tile boundaries.</li>
	 *   <li>The two {@code *Clipped} modes cut. Each gets its own CTE that unions its
	 *       polygons within the tile, and the rendered geometry expression grows from
	 *       {@code l.geom} outward: an {@code ST_Intersection} per {@code insideClipped}
	 *       mask, then an {@code ST_Difference} per {@code outsideClipped} mask. The two
	 *       groups can be applied in either order -- intersection and difference are set
	 *       operations, {@code (A ∩ B) \ C} and {@code (A \ C) ∩ B} are the same set --
	 *       so applying every {@code insideClipped} mask before every {@code
	 *       outsideClipped} one, regardless of how the caller ordered {@code masks},
	 *       costs nothing in correctness and keeps this loop simple.</li>
	 * </ul>
	 */
	private String query(String tableName, Collection<String> attributeColumns, List<ClipMask> masks) {
		String attributes = selectedAttributes(attributeColumns);
		String layerTable = SqlIdentifier.quoteLayerTable(tableName);
		if (masks.isEmpty()) {
			return TILE_QUERY.formatted(attributes, layerTable);
		}

		StringBuilder ctes = new StringBuilder();
		StringBuilder from = new StringBuilder(layerTable).append(" l, bounds b");
		StringBuilder where = new StringBuilder("l.geom && b.native");
		String geom = "l.geom";

		int cteIndex = 0;
		for (ClipMask mask : masks) {
			if (!MODE_INSIDE_CLIPPED.equals(mask.mode())) {
				continue;
			}
			String cte = "mask_" + cteIndex++;
			ctes.append(",\n").append(clippedMaskCte(cte, mask.tableName()));
			from.append(", ").append(cte);
			where.append(" AND l.geom && ").append(cte).append(".geom");
			geom = "ST_Intersection(%s, %s.geom)".formatted(geom, cte);
		}
		for (ClipMask mask : masks) {
			if (!MODE_OUTSIDE_CLIPPED.equals(mask.mode())) {
				continue;
			}
			String cte = "mask_" + cteIndex++;
			ctes.append(",\n").append(clippedMaskCte(cte, mask.tableName()));
			from.append(", ").append(cte);
			geom = "CASE WHEN %1$s.geom IS NULL THEN %2$s ELSE ST_Difference(%2$s, %1$s.geom) END"
					.formatted(cte, geom);
		}
		for (ClipMask mask : masks) {
			if (MODE_INSIDE_WHOLE.equals(mask.mode())) {
				where.append(" AND EXISTS (").append(wholeMaskSubquery(mask.tableName())).append(")");
			}
			else if (MODE_OUTSIDE_WHOLE.equals(mask.mode())) {
				where.append(" AND NOT EXISTS (").append(wholeMaskSubquery(mask.tableName())).append(")");
			}
		}

		return """
				WITH bounds AS (
				  SELECT ST_TileEnvelope(:z, :x, :y) AS merc,
				         ST_Transform(ST_TileEnvelope(:z, :x, :y), :srid) AS native
				)%s
				SELECT ST_AsMVT(tile, 'layer', 4096, 'geom', 'fid')
				FROM (
				  SELECT l.fid,%s
				         ST_AsMVTGeom(ST_Transform(%s, 3857), b.merc, 4096, 64, true) AS geom
				  FROM %s
				  WHERE %s
				) AS tile
				WHERE tile.geom IS NOT NULL
				"""
				.formatted(ctes, attributes, geom, from, where);
	}

	/**
	 * A CTE unioning one {@code *Clipped} mask's polygons within the tile.
	 *
	 * <p>{@code ST_Union} is not optional. Without it, a feature crossing two mask
	 * polygons would join twice and reach {@code ST_AsMVT} as two overlapping rows for
	 * the same {@code fid} -- invisible on an opaque fill, but visibly darker wherever
	 * semi-transparent fills overlap.
	 *
	 * <p>The mask is unioned only within the tile ({@code m.geom && b.native}), never
	 * across the whole table: a mask layer can hold thousands of polygons, and the tile
	 * envelope is what keeps this cheap. That is safe here specifically because the
	 * {@code *Clipped} modes only ever cut geometry, never decide whether a whole
	 * feature is kept -- see {@link #wholeMaskSubquery} for why the {@code *Whole} modes
	 * cannot take the same shortcut.
	 */
	private static String clippedMaskCte(String cteName, String maskTableName) {
		return "%s AS (\n  SELECT ST_Union(m.geom) AS geom FROM %s m, bounds b WHERE m.geom && b.native\n)"
				.formatted(cteName, SqlIdentifier.quoteLayerTable(maskTableName));
	}

	/**
	 * The correlated {@code EXISTS} subquery an {@code insideWhole}/{@code outsideWhole}
	 * mask filters with, run against the mask's full table rather than a tile-bounded
	 * union (CONTRACT.md phase 21).
	 *
	 * <p>This is the point at which the two {@code *Whole} modes could easily be built
	 * wrong: whether a feature touches the mask is a property of the whole feature, never
	 * of the tile it happens to be rendered into. Filtering against a tile-bounded union
	 * -- the shortcut {@link #clippedMaskCte} takes for the {@code *Clipped} modes --
	 * would make a feature vanish in exactly the tiles where it does not itself touch the
	 * mask, so a long feature meant to be kept whole would fall apart across tile
	 * boundaries. {@code m.geom && l.geom} keeps this affordable anyway: it still hits
	 * the mask table's GiST index, one lookup per candidate feature.
	 */
	private static String wholeMaskSubquery(String maskTableName) {
		return "SELECT 1 FROM %s m WHERE m.geom && l.geom AND ST_Intersects(m.geom, l.geom)"
				.formatted(SqlIdentifier.quoteLayerTable(maskTableName));
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
