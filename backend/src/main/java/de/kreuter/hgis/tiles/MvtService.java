package de.kreuter.hgis.tiles;

import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.common.ProjectionDomain;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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
	 * WGS84's semi-major axis in metres -- the sphere Web Mercator (EPSG:3857) projects
	 * onto. The textbook constant behind {@code 2 * pi * r}, the world's circumference at
	 * the equator; the same value underlies the 156543.033928 m/pixel figure documented in
	 * {@link de.kreuter.hgis.wms.MapLayerService#SCALE_DENOMINATOR_AT_ZOOM_0}.
	 */
	private static final double WEB_MERCATOR_EARTH_RADIUS_METRES = 6_378_137.0;

	/**
	 * How many heatmap points a line spanning a whole tile's width gets, independent of
	 * zoom -- see {@link #heatmapPointSpacingMetres} for how that turns into an actual
	 * spacing. Chosen against the contract's default {@code radius} of 30 screen points at
	 * a tile rendered around 512 CSS pixels wide: 512 / 32 = 16 screen points between
	 * points, comfortably inside one radius, so neighbouring points' kernels overlap into a
	 * continuous glow instead of visible, separate blobs -- while still keeping the point
	 * count bounded no matter how long the underlying line actually is.
	 */
	private static final int HEATMAP_POINTS_ACROSS_TILE = 32;

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
	 * <p>Equivalent to calling the five-argument-longer overload with {@code heatmap} false
	 * and no geometry type -- every existing caller that never renders a heatmap tile.
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
		return renderTile(tableName, srid, attributeColumns, masks, null, false, z, x, y);
	}

	/**
	 * As above, for a layer whose style renders as a heatmap (CONTRACT.md heatmap
	 * package): the tile then carries points -- one per point feature unchanged, one per
	 * polygon, several evenly spaced along each line -- instead of the layer's own
	 * geometry. See {@link #heatmapGeometryExpression} for why and how.
	 *
	 * @param geometryType the layer's geometry type; read only when {@code heatmap} is
	 *                     true, so {@code null} is fine otherwise
	 * @param heatmap      whether the layer's current style is a heatmap renderer
	 */
	public RenderedTile renderTile(String tableName, int srid, Collection<String> attributeColumns,
			List<ClipMask> masks, GeometryType geometryType, boolean heatmap, int z, int x, int y) {
		RenderedTile result = jdbc
				.sql(query(tableName, attributeColumns, masks, geometryType, heatmap, srid, z, x, y))
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
		return explainTile(tableName, srid, attributeColumns, masks, null, false, z, x, y);
	}

	/** As above, for the heatmap-aware query -- see the matching {@code renderTile} overload. */
	String explainTile(String tableName, int srid, Collection<String> attributeColumns,
			List<ClipMask> masks, GeometryType geometryType, boolean heatmap, int z, int x, int y) {
		String sql = "EXPLAIN (ANALYZE, FORMAT JSON) "
				+ query(tableName, attributeColumns, masks, geometryType, heatmap, srid, z, x, y);
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
			GeometryType geometryType, boolean heatmap, int srid, int z, int x, int y) {
		String attributes = selectedAttributes(attributeColumns);
		String layerTable = SqlIdentifier.quoteLayerTable(tableName);
		String nativeBounds = nativeBounds(srid, z, x, y);

		StringBuilder ctes = new StringBuilder();
		StringBuilder from = new StringBuilder(layerTable).append(" l, bounds b");
		StringBuilder where = new StringBuilder("l.geom && b.native");
		String geom = heatmap ? heatmapGeometryExpression(geometryType, z, from) : "l.geom";

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
	 * The geometry a heatmap tile renders instead of {@code l.geom} (CONTRACT.md heatmap
	 * package, "Punkte im Kachelweg") -- MapLibre's heatmap layer only draws points, so a
	 * line or polygon layer has to be turned into some before it reaches the client. What
	 * that becomes depends on {@code geometryType}:
	 *
	 * <ul>
	 *   <li>{@code MULTIPOINT} needs no change at all -- it already is what a heatmap
	 *       draws.</li>
	 *   <li>{@code MULTIPOLYGON} (and {@code GEOMETRY}, grouped with it exactly as
	 *       {@link LayerStyleService#defaultSymbolFor} already groups them) becomes one
	 *       point per feature via {@code ST_PointOnSurface} -- never {@code ST_Centroid},
	 *       whose result for a U-shaped or ring-shaped outline can fall outside the
	 *       polygon entirely.</li>
	 *   <li>{@code MULTILINESTRING} becomes several points, evenly spaced along the line
	 *       -- see {@link #interpolatedLinePoints}.</li>
	 * </ul>
	 *
	 * @param from appended to when the geometry needs more than a scalar substitution --
	 *             {@code MULTILINESTRING} joins in a {@code LATERAL} row source, which
	 *             {@code MULTIPOINT} and {@code MULTIPOLYGON} do not need
	 */
	private String heatmapGeometryExpression(GeometryType geometryType, int z, StringBuilder from) {
		return switch (geometryType) {
			case MULTIPOINT -> "l.geom";
			case MULTIPOLYGON, GEOMETRY -> "ST_PointOnSurface(l.geom)";
			case MULTILINESTRING -> interpolatedLinePoints(z, from);
		};
	}

	/**
	 * Explodes each line feature into several point rows -- not one row with a multipoint
	 * geometry, on purpose: {@link #DEFAULT_MAX_FEATURES_PER_TILE} and the {@code candidates}
	 * {@code LIMIT} above are calibrated per row (roughly 19 bytes each, see that constant's
	 * own note), and a heatmap point is exactly that size. One row per point is what makes
	 * that limit mean the same thing for a heatmap tile as for any other -- see the class's
	 * own report for the measured point counts this produces and how they compare to the
	 * limit.
	 *
	 * <p>Seven {@code LATERAL} steps, each named for what it adds:
	 *
	 * <ol>
	 *   <li>{@code part}: {@code ST_Dump(l.geom)} -- {@code l.geom} is always the multi
	 *       form, and both {@code ST_LineLocatePoint} and {@code ST_LineInterpolatePoint}
	 *       below only accept a plain {@code LINESTRING}. Splitting first, before clipping,
	 *       is what lets step 4 measure a clipped piece's position against the <em>whole</em>
	 *       original part rather than against whatever fragment this one tile happens to
	 *       see -- the point this whole method exists to fix, below.</li>
	 *   <li>{@code partflags}: {@code NOT ST_IsSimple(part.geom)}, computed once per part
	 *       rather than once per clipped piece -- a self-crossing part can hand back several
	 *       {@code ln} pieces (see the review scenario in step 4's own note, where one
	 *       self-crossing four-point line clips into four pieces within a single tile), and
	 *       {@code ST_IsSimple} is an {@code O(n log n)} check over the <em>whole</em> part;
	 *       paying it once per part instead of once per piece is what keeps a tile with
	 *       several pieces of the same meandering line from paying it several times over.</li>
	 *   <li>{@code ln}: {@code ST_Dump(ST_CollectionExtract(ST_Intersection(part.geom,
	 *       b.native), 2))} -- clips that one part to the tile and splits the result into
	 *       plain linestrings again, since a clip can produce several disjoint pieces (a
	 *       line leaving and re-entering the tile). Without the clip, a long line (a river,
	 *       a road) would be walked along its <em>entire</em> length for every tile its
	 *       bounding box merely touches, most of the resulting points immediately discarded
	 *       by {@code ST_AsMVTGeom} -- exactly the "millions of points" failure mode the
	 *       contract warns against, just moved from a too-fine spacing to a too-long line.
	 *       {@code ST_CollectionExtract(..., 2)} is what keeps this safe against every shape
	 *       {@code ST_Intersection} can hand back at a tile edge, proven against a real
	 *       PostGIS: a line only grazing the tile boundary -- tangent to a corner or an edge
	 *       -- intersects to a bare {@code POINT}, and {@code ST_LineInterpolatePoint} errors
	 *       outright ("1st arg isn't a line") if that reaches it directly.
	 *       {@code ST_CollectionExtract} turns that {@code POINT} (or a
	 *       {@code GEOMETRYCOLLECTION EMPTY}, or a {@code NULL} in the degenerate case of a
	 *       not-yet-existing mask) into an empty {@code LINESTRING}, which dumps to zero rows
	 *       rather than raising an error -- so a tile that happens to catch a line exactly at
	 *       its edge renders instead of failing.</li>
	 *   <li>{@code loc}: {@code ST_LineLocatePoint(part.geom, ST_StartPoint(ln.geom))} --
	 *       PostGIS's own, single answer for where this piece's start sits along the whole
	 *       part, {@code 0..1}. Kept as its own step, rather than inlined into {@code phase}
	 *       below, purely so the review scenario's second candidate (step 5) can refer to it
	 *       by name instead of recomputing it.</li>
	 *   <li>{@code phase}: how far this clipped piece's own start point sits along the whole
	 *       original {@code part}, in the layer's own metres -- {@code loc.t1 *
	 *       ST_Length(part.geom)} for the ordinary case, but <strong>not always</strong> for a
	 *       part that crosses itself. {@code ST_LineLocatePoint} finds the closest point on
	 *       the curve to the point it is given and returns only one fraction -- for a simple
	 *       part that is fine, since every point on it occurs at exactly one fraction, but a
	 *       part that crosses itself visits its own crossing point at <em>two</em> different
	 *       fractions, and {@code ln.geom}'s start can legitimately be either one. Reviewed and
	 *       reproduced directly in SQL with the four-point self-crossing line {@code
	 *       LINESTRING(0 0, 10 10, 10 0, 0 10)} (crossing itself at {@code (5,5)}, the
	 *       geometry {@code MvtServiceHeatmapTest} tests against) clipped to a small box
	 *       straddling that crossing: the clip produces four pieces, two of which start at
	 *       {@code (5,5)} -- {@code ST_LineLocatePoint} answers {@code 0,1847} (7,07 m along a
	 *       38,28 m line) for <em>both</em>, which is correct for the piece continuing along
	 *       the first branch and wrong by 24,14 m for the piece continuing along the third,
	 *       whose true phase is {@code 0,8153} (31,21 m) -- silently: no error, no NULL, just a
	 *       raster offset computed against the wrong location, so that piece's points land
	 *       spaced correctly relative to a stretch of line they do not belong to. Self-crossing
	 *       lines are not a rare shape for this application to render as a heatmap -- a
	 *       meandering river, a hiking trail with a loop, a railway with a reversing loop are
	 *       exactly this shape, not an edge case of it.
	 *       <p>Fixed by verification rather than by trying to enumerate every occurrence:
	 *       {@code ST_LineLocatePoint} always finds the <em>first</em> (smallest-fraction)
	 *       occurrence, so if that is wrong for this particular piece, the right one is
	 *       necessarily later along the part -- found by re-running the same search, but only
	 *       on the remainder of the part strictly after {@code loc.t1}
	 *       ({@code ST_LineSubstring(part.geom, loc.t1 + epsilon, 1.0)}, the {@code epsilon}
	 *       existing only so the search does not immediately re-find the very point it just
	 *       started from). That second candidate is only computed at all when {@code
	 *       partflags.self_intersects} -- a simple part never needs it, and skips straight to
	 *       using {@code loc.t1}, unchanged from before this fix. Whichever of the (at most
	 *       two) candidates is right is then told apart the same way a human would check it:
	 *       walking <em>forward</em> from that candidate by {@code ln.geom}'s own length must
	 *       land back on {@code ln.geom}'s own end point ({@code ST_LineInterpolatePoint(
	 *       part.geom, candidate + ST_Length(ln.geom)/ST_Length(part.geom))} compared against
	 *       {@code ST_EndPoint(ln.geom)} by {@code ST_Distance}) -- the wrong candidate's
	 *       forward projection lands somewhere else on the part entirely, so {@code ORDER BY}
	 *       that distance and taking the closest picks the candidate that actually reproduces
	 *       this piece, not just a point that happens to match its start. A part that crosses
	 *       the very same coordinate a third time is not caught by this (only two candidates
	 *       are ever generated) -- unreached in practice, since it needs the same exact
	 *       coordinate visited three separate times by one feature, not merely three
	 *       self-crossings.</li>
	 *   <li>{@code raster}: {@code clip_len} (this piece's own length) and
	 *       {@code first_offset} -- the distance, from this piece's own start, to the next
	 *       point on a grid measured from the <em>whole part's</em> start, spacing apart.
	 *       {@code first_offset = spacing - (phase mod spacing)}, written with
	 *       {@code FLOOR} since SQL has no {@code mod} for {@code double precision}.</li>
	 *   <li>{@code pt}: one row per point, at local fractions {@code (first_offset +
	 *       gs * spacing) / clip_len} for {@code gs} from a {@code generate_series}, plus a
	 *       fallback row for a part shorter than one spacing unit end to end -- see the class's
	 *       own note on this fallback's own history, below.</li>
	 * </ol>
	 *
	 * <p><strong>Why the grid is measured from the whole part, not from each clipped
	 * piece's own start</strong> -- found on the review that followed this method's first
	 * version, which did exactly that (fraction as a share of {@code ln.geom}'s own length,
	 * points at {@code n * fraction}): every tile then started its own point sequence at
	 * fraction 0 relative to whatever fragment of the line it happened to see, so two
	 * neighbouring tiles' fragments of the very same line agreed on no shared point at all.
	 * Measured on two real, adjacent tiles at z=12: the last point before the boundary sat
	 * 158,7 m before it, the first point after sat a full spacing unit (305,7 m) after it --
	 * a combined gap of 464,5 m, about one and a half times the ordinary spacing, at every
	 * tile edge a line crosses. Anchoring the grid to {@code part.geom} -- the same geometry
	 * on both sides of the boundary, since a feature's row and its full geometry do not
	 * change from one tile to the next -- gives both tiles' clips the same answer for
	 * "how far from the part's own start is the next grid point", so they agree on where the
	 * points fall and the seam closes. Confirmed the same way the gap was found: two real,
	 * adjacent tiles, this time producing the same point positions across the shared edge
	 * (see {@code MvtServiceHeatmapTest#lineCrossingATileBoundaryProducesTheSameGridOnBothSides},
	 * scanning several line placements rather than trusting one -- see that test's own note).
	 *
	 * <p>When a part is not clipped at all -- the ordinary case, a line entirely inside one
	 * tile -- {@code phase} is 0 and {@code first_offset} collapses to exactly {@code
	 * spacing}, which reproduces the original, unaligned formula's own points exactly: {@code
	 * n * spacing} for every {@code n} up to {@code floor(length / spacing)}. The realignment
	 * only ever changes anything for a piece that a tile boundary actually cut.
	 *
	 * <p><strong>The fallback's own history</strong> -- its first version fired whenever
	 * <em>this clipped piece</em> was too short to reach a grid point on its own ({@code
	 * first_offset > clip_len}), placing the rescue point at that piece's own raw, unaligned
	 * end -- which, for the ordinary case a tile boundary actually cuts a piece off, <em>is</em>
	 * the tile boundary. Reviewed and reproduced: a line left with a short (~196 m, under one
	 * spacing unit) remainder in one tile before continuing on into the next carries a
	 * fallback point sitting exactly on that boundary, whose distance to the neighbouring
	 * tile's own next aligned point depends only on where the boundary happens to fall in the
	 * global raster phase -- and can be made arbitrarily small. Scanning the line's start
	 * position across the boundary reproduced a gap shrinking continuously from 110 m down to
	 * 14 cm before the fallback stopped firing at all: two points 14 cm apart, on a heatmap,
	 * is a bright seam sitting exactly on the one kind of location the phase realignment above
	 * exists to heal.
	 * <p>The question the fallback answers was wrong: not "is this <em>piece</em> short", but
	 * "would this <em>feature</em> vanish everywhere without it" -- a piece left short by
	 * clipping is not missing anything, its weight belongs to the aligned grid point the
	 * neighbouring tile places right next to the cut, which is exactly what the phase
	 * realignment above already guarantees exists. Only a part whose own, <em>unclipped</em>
	 * length is under one spacing unit -- so short that {@code floor(length / spacing)} is
	 * {@code 0} everywhere, in every tile it could ever be clipped into -- has no grid point
	 * anywhere and needs the rescue. The fallback's condition is therefore {@code
	 * ST_Length(part.geom) < spacing}, not a comparison against {@code clip_len}, and its point
	 * sits at {@code part.geom}'s own midpoint rather than {@code ln.geom}'s raw end: a fixed
	 * location on the whole feature, the same regardless of which tile clipped it or where, so
	 * it can only ever fall inside <em>one</em> tile's {@code b.native} and be kept there --
	 * {@code ST_AsMVTGeom} discards it, silently, in every other tile that also evaluates this
	 * same {@code WHERE}, the same way it already discards any other point outside the tile.
	 * Confirmed against the same scan that found the bug: scanning the same boundary-crossing
	 * line's start position no longer produces a fallback point in the short-remainder tile at
	 * all (see {@code MvtServiceHeatmapTest#aBoundaryCutRemainderProducesNoNearDuplicate}),
	 * while {@link MvtServiceHeatmapTest#aShortLineStillGetsOnePoint} -- a part that is short
	 * end to end, not merely left short by a cut -- keeps its one point exactly as before.
	 *
	 * <p>Every point produced this way carries its parent line's own attribute values
	 * unchanged, including the weight field -- see the class's own report for why that
	 * value is <em>not</em> divided by the point count.
	 */
	private static String interpolatedLinePoints(int z, StringBuilder from) {
		String spacing = String.format(Locale.ROOT, "%.6f", heatmapPointSpacingMetres(z));
		from.append(",\n  LATERAL ST_Dump(l.geom) AS part")
				.append(",\n  LATERAL (SELECT NOT ST_IsSimple(part.geom) AS self_intersects) AS partflags")
				.append(",\n  LATERAL ST_Dump(ST_CollectionExtract(ST_Intersection(part.geom, b.native), 2)) AS ln")
				.append(",\n  LATERAL (SELECT ST_LineLocatePoint(part.geom, ST_StartPoint(ln.geom)) AS t1) AS loc")
				.append(",\n  LATERAL (SELECT (candidate.t * ST_Length(part.geom)) AS clip_start FROM (")
				.append("SELECT loc.t1 AS t ")
				.append("UNION ALL ")
				.append("SELECT LEAST(loc.t1 + 1e-9, 1.0) + ST_LineLocatePoint(")
				.append("ST_LineSubstring(part.geom, LEAST(loc.t1 + 1e-9, 1.0), 1.0), ST_StartPoint(ln.geom)) ")
				.append("* (1.0 - LEAST(loc.t1 + 1e-9, 1.0)) ")
				.append("WHERE partflags.self_intersects AND loc.t1 < 1.0")
				.append(") AS candidate ")
				.append("ORDER BY ST_Distance(ST_LineInterpolatePoint(part.geom, LEAST(1.0, candidate.t + ")
				.append("ST_Length(ln.geom) / NULLIF(ST_Length(part.geom), 0))), ST_EndPoint(ln.geom)) ASC, candidate.t ASC ")
				.append("LIMIT 1) AS phase")
				.append(",\n  LATERAL (SELECT ST_Length(ln.geom) AS clip_len, ")
				.append(spacing).append(" - (phase.clip_start - ").append(spacing)
				.append(" * FLOOR(phase.clip_start / ").append(spacing).append(")) AS first_offset) AS raster")
				.append(",\n  LATERAL (")
				.append("SELECT ST_LineInterpolatePoint(ln.geom, LEAST(1.0, ")
				.append("(raster.first_offset + gs * ").append(spacing).append(") / NULLIF(raster.clip_len, 0))) AS geom ")
				.append("FROM generate_series(0, FLOOR((raster.clip_len - raster.first_offset) / ")
				.append(spacing).append(")::int) AS gs")
				.append(" UNION ALL ")
				.append("SELECT ST_LineInterpolatePoint(part.geom, 0.5) AS geom ")
				.append("WHERE ST_Length(part.geom) < ").append(spacing)
				.append(") AS pt");
		return "pt.geom";
	}

	/**
	 * The distance between heatmap points along a line, in the layer's own storage-CRS
	 * metres (the same assumption {@link #SEGMENT_METRES} already rests on for this class:
	 * a layer's storage CRS is a projected, metric one).
	 *
	 * <p>A fixed metre figure would fail either half of the contract's own warning -- too
	 * fine at a low zoom (millions of points), too coarse at a high one (an invisible
	 * trickle) -- because the same real-world distance covers a wildly different number of
	 * screen pixels depending on zoom. This instead keeps {@link #HEATMAP_POINTS_ACROSS_TILE}
	 * roughly constant per tile at every zoom: a tile's ground width at the equator halves
	 * with each zoom level ({@code 2 * pi * r / 2^z}), so dividing that by a fixed point
	 * count yields a spacing that shrinks in step -- coarse where a tile spans a lot of
	 * ground, fine where it spans little.
	 *
	 * <p>Deliberately the equatorial width, not one adjusted for the tile's actual latitude:
	 * {@link #SEGMENT_METRES} and {@link #tileFootprint} already accept the same
	 * simplification elsewhere in this class. The distortion this trades away is not the
	 * few percent that phrase might suggest -- a Web Mercator tile's real ground width at
	 * latitude {@code lat} is {@code cos(lat)} of the equatorial figure this method uses, and
	 * at Hamburg's 53,55° that is {@code cos(53,55°) ≈ 0,594}: measured directly (see the
	 * class's own report), a full-tile-width line there carries about 18 points, not the 32
	 * {@link #HEATMAP_POINTS_ACROSS_TILE} targets at the equator. That changes how many
	 * points a line gets -- and always in the safe direction, fewer rather than more, since
	 * {@code cos(lat) <= 1} everywhere -- never whether the query is correct.
	 */
	private static double heatmapPointSpacingMetres(int z) {
		double tileWidthMetres = 2 * Math.PI * WEB_MERCATOR_EARTH_RADIUS_METRES / Math.scalb(1.0, z);
		return tileWidthMetres / HEATMAP_POINTS_ACROSS_TILE;
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
