package de.kreuter.hgis.tiles;

import de.kreuter.hgis.common.ProjectionDomain;
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
 * table. Where the tile is too wide for its CRS to describe at all, that narrowing is
 * given up rather than faked -- see {@link #nativeBounds}.
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
			         %s AS native
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
	 * How finely the tile envelope is sampled before it is transformed, in Web Mercator
	 * metres -- roughly one degree at the equator.
	 *
	 * <p>{@code ST_Transform} moves vertices, not edges. A tile envelope has four of them,
	 * and the projected image of the straight lines between them is curved, so the bounding
	 * box of the four transformed corners can be narrower than the area the tile really
	 * covers -- and every feature in the difference would silently drop out of the tile. Any
	 * tile below about zoom 8 is wide enough for that to matter; above it the envelope is
	 * shorter than one segment and this costs nothing at all.
	 */
	private static final int SEGMENT_METRES = 100_000;

	/**
	 * The stand-in for a tile envelope that cannot be expressed in the layer's CRS: a box so
	 * large that {@code l.geom && b.native} is true for every row.
	 *
	 * <p>That is not a shortcut, it is the correct answer. The {@code &&} predicate exists to
	 * let the GiST index narrow the scan; what actually decides a tile's content is
	 * {@code ST_AsMVTGeom}, which clips against {@code b.merc} and returns NULL for anything
	 * outside -- filtered out by the outer {@code WHERE}. Dropping the narrowing therefore
	 * costs a sequential scan and changes nothing about the bytes that come back. Which is
	 * the whole point: this case only arises at the low zoom levels where the tile covers
	 * the entire layer anyway, so there was nothing to narrow.
	 */
	private static final String UNBOUNDED_NATIVE = "ST_MakeEnvelope(-1e12, -1e12, 1e12, 1e12, :srid)";

	/** The four clip modes {@code layer.clip_mode} may carry (CONTRACT.md phase 21). */
	private static final String MODE_INSIDE_WHOLE = "insideWhole";
	private static final String MODE_INSIDE_CLIPPED = "insideClipped";
	private static final String MODE_OUTSIDE_WHOLE = "outsideWhole";
	private static final String MODE_OUTSIDE_CLIPPED = "outsideClipped";

	/** One mask acting on a layer's tile: the table to cut against, and how. */
	public record ClipMask(String tableName, String mode) {}

	private final JdbcClient jdbc;
	private final ProjectionDomain projectionDomain;

	MvtService(JdbcClient jdbc, ProjectionDomain projectionDomain) {
		this.jdbc = jdbc;
		this.projectionDomain = projectionDomain;
	}

	/**
	 * Renders one tile. Returns {@code null} when the tile has no features -- callers
	 * turn that into a 204, never an empty byte array with a 200.
	 *
	 * @param attributeColumns column names to carry as tile properties, resolved from the
	 *                         layer's style through {@code layer_field}; never a name that
	 *                         came out of the style document itself
	 * @param masks            the masks acting on this layer, bottom-most first, or empty
	 *                         to render unclipped -- never {@code null}. An empty list
	 *                         produces exactly the query this method ran before
	 *                         CONTRACT.md phase 19 introduced clip masks at all. The
	 *                         caller decides which masks apply to this particular layer;
	 *                         passing one here always clips, regardless of z-index.
	 */
	public byte[] renderTile(String tableName, int srid, Collection<String> attributeColumns,
			List<ClipMask> masks, int z, int x, int y) {
		byte[] mvt = jdbc.sql(query(tableName, attributeColumns, masks, srid, z, x, y))
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
		String sql = "EXPLAIN (ANALYZE, FORMAT JSON) "
				+ query(tableName, attributeColumns, masks, srid, z, x, y);
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
	 *   <li>The two {@code *Whole} modes filter, they do not cut geometry, and each keeps
	 *       only unambiguous features: {@code insideWhole} those lying entirely within the
	 *       mask ({@link #fullyInsidePredicate}), {@code outsideWhole} those touching it
	 *       nowhere ({@link #touchesMaskSubquery}, negated). A feature straddling the mask
	 *       edge appears in neither -- the {@code *Clipped} modes are what handles those.
	 *       Both run against the mask's whole table, never a tile-bounded union, so a
	 *       feature is kept or dropped as a whole rather than split across tile
	 *       boundaries.</li>
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
	private String query(String tableName, Collection<String> attributeColumns, List<ClipMask> masks,
			int srid, int z, int x, int y) {
		String attributes = selectedAttributes(attributeColumns);
		String layerTable = SqlIdentifier.quoteLayerTable(tableName);
		String nativeBounds = nativeBounds(srid, z, x, y);
		if (masks.isEmpty()) {
			return TILE_QUERY.formatted(nativeBounds, attributes, layerTable);
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
				where.append(" AND ").append(fullyInsidePredicate(mask.tableName()));
			}
			else if (MODE_OUTSIDE_WHOLE.equals(mask.mode())) {
				where.append(" AND NOT EXISTS (").append(touchesMaskSubquery(mask.tableName())).append(")");
			}
		}

		return """
				WITH bounds AS (
				  SELECT ST_TileEnvelope(:z, :x, :y) AS merc,
				         %s AS native
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
				.formatted(nativeBounds, ctes, attributes, geom, from, where);
	}

	/**
	 * The tile envelope in the layer's own CRS -- what every {@code &&} in this query
	 * compares against.
	 *
	 * <p>Transformed only where the transform means anything. A projected CRS covers a part
	 * of the globe, not all of it: PROJ rejects a point 81° or more from UTM32's central
	 * meridian outright, and a Web Mercator tile is that wide from roughly zoom 4 downwards
	 * -- which used to end the request in a 500 rather than a picture. Where the tile
	 * reaches past what {@link ProjectionDomain} vouches for, the envelope is therefore
	 * replaced by {@link #UNBOUNDED_NATIVE}; see that constant for why the tile still comes
	 * out exactly the same.
	 */
	private String nativeBounds(int srid, int z, int x, int y) {
		double[] footprint = tileFootprint(z, x, y);
		if (!projectionDomain.covers(srid, footprint[0], footprint[1], footprint[2], footprint[3])) {
			return UNBOUNDED_NATIVE;
		}
		return "ST_Transform(ST_Segmentize(ST_TileEnvelope(:z, :x, :y), " + SEGMENT_METRES + "), :srid)";
	}

	/**
	 * What {@code ST_TileEnvelope(z, x, y)} covers in lng/lat, as
	 * {@code minLng, minLat, maxLng, maxLat}.
	 *
	 * <p>Computed here rather than asked of the database: it is the closed form of the XYZ
	 * scheme, it decides which of two queries to build in the first place, and a round trip
	 * per tile request to learn something arithmetic would be a poor trade.
	 */
	private static double[] tileFootprint(int z, int x, int y) {
		double tilesPerAxis = Math.scalb(1.0, z);
		return new double[] {
				x / tilesPerAxis * 360.0 - 180.0,
				latitudeOf(y + 1, tilesPerAxis),
				(x + 1) / tilesPerAxis * 360.0 - 180.0,
				latitudeOf(y, tilesPerAxis) };
	}

	/** The northern edge of tile row {@code row}: the inverse Web Mercator of its y. */
	private static double latitudeOf(int row, double tilesPerAxis) {
		return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * row / tilesPerAxis))));
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
	 * The predicate an {@code insideWhole} mask filters with: the feature lies
	 * <em>entirely</em> within the mask (CONTRACT.md phase 21). A feature crossing the
	 * mask edge is not shown at all -- the two {@code *Clipped} modes exist for those.
	 *
	 * <p>Note what this is not: an {@code EXISTS} over the mask's rows, the shape
	 * {@link #touchesMaskSubquery} uses. {@code ST_Within} against one polygon at a time
	 * would drop a feature that lies well inside the masked area but straddles the seam
	 * between two adjacent mask polygons -- inside the mask as a whole, inside neither
	 * polygon alone. The union is therefore taken first and the containment tested
	 * against it.
	 *
	 * <p>{@code m.geom && l.geom} narrows that union to the polygons whose bounding box
	 * meets the feature at all. That is free of charge in correctness -- a polygon too
	 * far away to share a bounding box cannot help cover the feature -- and it is what
	 * keeps the union small and the mask table's GiST index in play. A mask that reaches
	 * nowhere near the feature unions to {@code NULL}, {@code ST_Within(geom, NULL)} is
	 * {@code NULL}, and the feature drops out. Which is correct: nothing lies inside a
	 * mask that is not there.
	 *
	 * <p>The union deliberately does not stop at the tile envelope, for the same reason
	 * spelled out in {@link #touchesMaskSubquery}: containment is a property of the whole
	 * feature, never of the tile it happens to be rendered into.
	 */
	private static String fullyInsidePredicate(String maskTableName) {
		return "ST_Within(l.geom, (SELECT ST_Union(m.geom) FROM %s m WHERE m.geom && l.geom))"
				.formatted(SqlIdentifier.quoteLayerTable(maskTableName));
	}

	/**
	 * The correlated {@code EXISTS} subquery an {@code outsideWhole} mask filters with,
	 * negated by the caller: the feature must touch no mask polygon at all
	 * (CONTRACT.md phase 21). A feature crossing the mask edge is not shown -- exactly
	 * mirroring {@link #fullyInsidePredicate}, so the two {@code *Whole} modes each show
	 * only unambiguous cases and neither shows a straddler.
	 *
	 * <p>No union is needed here, unlike for containment: touching no single polygon and
	 * touching none of their union are the same statement.
	 *
	 * <p>This is the point at which the two {@code *Whole} modes could easily be built
	 * wrong: whether a feature meets the mask is a property of the whole feature, never
	 * of the tile it happens to be rendered into. Filtering against a tile-bounded union
	 * -- the shortcut {@link #clippedMaskCte} takes for the {@code *Clipped} modes --
	 * would make a feature vanish in exactly the tiles where it does not itself meet the
	 * mask, so a long feature meant to be kept whole would fall apart across tile
	 * boundaries. {@code m.geom && l.geom} keeps this affordable anyway: it still hits
	 * the mask table's GiST index, one lookup per candidate feature.
	 */
	private static String touchesMaskSubquery(String maskTableName) {
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
