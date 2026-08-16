package de.kreuter.hgis.tiles;

import de.kreuter.hgis.common.ProjectionDomain;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
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

	/**
	 * How many features one tile may carry at most (this class's answer to the "4,35 MB
	 * fuer eine Kachel" finding). A {@code LIMIT} inside {@code candidates} below, not a
	 * client-side truncation of the finished tile: cutting the SQL result set is what
	 * keeps the oversized case from ever reaching {@code ST_AsMVT}, {@code ST_AsMVTGeom}
	 * or the wire in the first place.
	 *
	 * <p>230.000 simple points measured 4.350.410 bytes -- about 19 bytes each. This
	 * limit keeps that same worst case, and any layer with real attributes on top,
	 * comfortably under 2 MB rather than picking the smallest number that still looks
	 * safe: a tile that renders visibly incomplete (see {@link #truncated}) is always
	 * the caller's cue to zoom in, so there is little to gain from limiting harder than
	 * this and only lost detail to show for it.
	 */
	static final int DEFAULT_MAX_FEATURES_PER_TILE = 50_000;

	/**
	 * Answered alongside the tile bytes: the {@code LIMIT} above cuts silently in SQL, so
	 * without this a truncated tile and a complete one would be byte-for-byte
	 * indistinguishable to a caller. A limit that only shrinks the response without ever
	 * saying so is the expensive kind of bug (CONTRACT.md) -- the map looks finished and
	 * is not. {@link de.kreuter.hgis.tiles.TileController} turns {@link #truncated()}
	 * into a response header a client can act on; either way it also reaches the log,
	 * since nobody necessarily looks at the header on any single request.
	 */
	public record RenderedTile(byte[] mvt, boolean truncated) {}

	/**
	 * The tie-break {@code candidates} falls back to once {@code ST_Area + ST_Length}
	 * ties -- which, for a point layer, is every row, always: neither function means
	 * anything for a point, so the whole ranking used to fall straight through to
	 * {@code fid}. {@code fid} is {@code GENERATED ALWAYS AS IDENTITY}, i.e. insertion
	 * order, and nothing about ingest ever shuffles that -- a source sorted by district,
	 * the ordinary shape of a government export, keeps that order into the table. The
	 * result was not a thinned-out layer, it was one district kept whole and its
	 * neighbour dropped whole: reviewed and reproduced with two 20.000-point "Bezirke"
	 * in one tile, limit 5.000 -- 5.000 from the first, 0 from the second.
	 *
	 * <p>This scatters {@code fid} instead of using it directly: {@code (fid * A) mod M}
	 * with {@code M = 2^31 - 1} (a Mersenne prime) and {@code A = 2654435761}, the Knuth
	 * multiplicative-hashing constant ({@code 2^32} times the golden ratio's fractional
	 * part, rounded to the nearest prime -- the standard choice for scattering small,
	 * sequential integers, used for exactly that in hash tables and noise functions).
	 * Multiplying by a unit modulo a prime is a bijection, so this loses no fids -- but
	 * bijective alone would not have been enough. An earlier version of this multiplied
	 * by {@code 16807} instead (the classic Park-Miller generator constant) and measured
	 * as 2.500/2.500 in theory, 5.000/0 in the review's own reproduction: {@code 16807} is
	 * so small relative to {@code M} that {@code fid * 16807} does not wrap {@code M} at
	 * all across any realistic table's fid range, leaving the map linear -- and therefore
	 * exactly the insertion order it was meant to break up -- in every practical case. The
	 * review's own two-district table pins that regression down; see the class's test
	 * {@code MvtServiceSpatialClumpingTest}, which fails on {@code 16807} and passes on
	 * {@code 2654435761}. {@code A}'s size relative to {@code M} is what actually matters:
	 * large enough that a single increment of {@code fid} already wraps {@code M}, so
	 * neighbouring fids -- and anything correlated with them, like a spatial clump -- land
	 * far apart in the ranking. Measured on the review's exact scenario (two 20.000-point
	 * districts, one tile, limit 5.000): 2.499/2.501.
	 *
	 * <p>{@code (fid + 1) mod M} first keeps every fid inside the map's domain without
	 * ever overflowing {@code bigint} -- the multiply that follows is then at most
	 * {@code (M - 1) * A}, about {@code 5.7 * 10^18}, comfortably short of {@code bigint}'s
	 * {@code 2^63 - 1} (about {@code 9.2 * 10^18}) -- and steers clear of {@code fid = -1},
	 * the one value the bijection sends to {@code 0} regardless of {@code A}.
	 *
	 * <p>Plain arithmetic on purpose, not {@code hashtext} or {@code hashint8}: those are
	 * PostgreSQL-internal hash functions explicitly reserved to change between major
	 * versions (why {@code pg_upgrade} can demand a {@code REINDEX} of a hash index), so
	 * two servers a version apart could truncate the very same tile into two different,
	 * equally valid selections -- silently, since nothing about that changes {@code
	 * dataVersion}, {@code styleVersion} or the tile's {@code ETag}. {@code +}, {@code *}
	 * and {@code %} on {@code bigint} carry no such reservation; this expression means
	 * exactly the same thing on any PostgreSQL version, past or future.
	 *
	 * <p>The trailing plain {@code l.fid} exists only for a total order: two fids can
	 * share a scattered value if they are exactly {@code M} apart, a gap no real layer
	 * reaches, so this never changes which rows a tile shows -- only, in that one
	 * unreachable case, which order ties would otherwise leave unresolved.
	 */
	private static final String SPATIAL_SCATTER = "((l.fid + 1) % 2147483647) * 2654435761 % 2147483647";

	private static final String TILE_QUERY = """
			WITH bounds AS (
			  SELECT ST_TileEnvelope(:z, :x, :y) AS merc,
			         %s AS native
			)%s,
			candidates AS MATERIALIZED (
			  SELECT l.fid,%s
			         ST_AsMVTGeom(ST_Transform(%s, 3857), b.merc, 4096, 64, true) AS geom
			  FROM %s
			  WHERE %s
			  ORDER BY (ST_Area(l.geom) + ST_Length(l.geom)) DESC, %s, l.fid
			  LIMIT :tileLimit
			)
			SELECT
			  (SELECT ST_AsMVT(t, 'layer', 4096, 'geom', 'fid')
			     FROM (SELECT * FROM candidates LIMIT :maxFeatures) t
			     WHERE t.geom IS NOT NULL) AS mvt,
			  (SELECT count(*) FROM candidates) > :maxFeatures AS truncated
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
	private final int maxFeaturesPerTile;

	@Autowired
	MvtService(JdbcClient jdbc, ProjectionDomain projectionDomain) {
		this(jdbc, projectionDomain, DEFAULT_MAX_FEATURES_PER_TILE);
	}

	/**
	 * Lets a test trigger {@link RenderedTile#truncated()} with a handful of rows
	 * instead of {@link #DEFAULT_MAX_FEATURES_PER_TILE}. Never used by Spring: the
	 * constructor above is the only one it can pick without ambiguity.
	 */
	MvtService(JdbcClient jdbc, ProjectionDomain projectionDomain, int maxFeaturesPerTile) {
		this.jdbc = jdbc;
		this.projectionDomain = projectionDomain;
		this.maxFeaturesPerTile = maxFeaturesPerTile;
	}

	/**
	 * Renders one tile. {@link RenderedTile#mvt()} is {@code null} when the tile has no
	 * features -- callers turn that into a 204, never an empty byte array with a 200.
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
	public RenderedTile renderTile(String tableName, int srid, Collection<String> attributeColumns,
			List<ClipMask> masks, int z, int x, int y) {
		RenderedTile result = jdbc.sql(query(tableName, attributeColumns, masks, srid, z, x, y))
				.param("z", z)
				.param("x", x)
				.param("y", y)
				.param("srid", srid)
				.param("tileLimit", maxFeaturesPerTile + 1)
				.param("maxFeatures", maxFeaturesPerTile)
				.query((rs, rowNum) -> new RenderedTile(rs.getBytes("mvt"), rs.getBoolean("truncated")))
				.single();
		byte[] mvt = result.mvt();
		return (mvt == null || mvt.length == 0) ? new RenderedTile(null, result.truncated()) : result;
	}

	/** What {@link RenderedTile#truncated()} was measured against -- for a log line only. */
	public int maxFeaturesPerTile() {
		return maxFeaturesPerTile;
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
				.param("tileLimit", maxFeaturesPerTile + 1)
				.param("maxFeatures", maxFeaturesPerTile)
				.query(String.class)
				.single();
	}

	/**
	 * Builds the query for {@code masks}, always through {@link #TILE_QUERY}: an empty
	 * {@code masks} list leaves {@code ctes} empty and {@code from}/{@code where}/
	 * {@code geom} at their unclipped defaults below, which is exactly the query this
	 * method ran before CONTRACT.md phase 19 introduced clip masks at all -- {@code
	 * candidates}' ranking and {@code LIMIT} aside. Otherwise built from parts (phase 21):
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

		return TILE_QUERY.formatted(nativeBounds, ctes, attributes, geom, from, where, SPATIAL_SCATTER);
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
