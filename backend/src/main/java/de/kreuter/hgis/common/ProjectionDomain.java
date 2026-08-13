package de.kreuter.hgis.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How far a request rectangle may reach before it can no longer be expressed in a layer's
 * storage CRS.
 *
 * <p>Both the tile query and the feature query narrow by the same trick: they transform the
 * requested rectangle into the layer's CRS and compare it against the untransformed
 * {@code geom} column, which is the only shape that can use that column's GiST index. The
 * trick has a limit, and the limit is not an edge case:
 *
 * <ul>
 *   <li>A projected CRS is only defined on part of the globe. {@code EPSG:25832} is a
 *       transverse Mercator around 9°E, and PROJ refuses any point 81° or more away from
 *       that meridian with "Point outside of projection domain" -- which is exactly what a
 *       Web Mercator tile covers from about zoom 4 downwards.</li>
 *   <li>Even where it is defined, it folds. Longitude 180° and longitude -180° both land
 *       near the central meridian in UTM32, so a rectangle spanning the whole world
 *       transforms into a line of zero width and matches nothing at all.</li>
 * </ul>
 *
 * <p>This class answers the one question that separates the two cases: does the requested
 * rectangle lie inside the window where the CRS behaves? Inside it, the caller transforms
 * as before and keeps its index. Outside it, the caller must fall back to something that
 * cannot be expressed in the layer's CRS at all -- see the two call sites for what each of
 * them falls back to.
 *
 * <p>The window comes from PROJ's own area of use for the CRS ({@code postgis_srs}),
 * widened by {@link #MARGIN_DEGREES}. The area of use alone would be far too narrow to use
 * directly: it is where a CRS is *meant* to be used, 6°E to 12°E for UTM32, while a
 * nationwide German layer in that CRS reaches past 15°E and projects perfectly well. The
 * margin is what turns "meant for" into "works for", and it is deliberately generous in one
 * direction only -- being too narrow costs a slower query, being too wide costs a failed
 * one.
 *
 * <p>One lookup per SRID, cached for the life of the application: a CRS definition cannot
 * change under a running server, and every tile request would otherwise pay for reading
 * PROJ's database again.
 */
@Component
public class ProjectionDomain {

	/**
	 * How far beyond its declared area of use a CRS is still treated as projectable.
	 *
	 * <p>Sized against the tightest CRS family the application actually stores data in: a
	 * UTM zone is declared for 6° of longitude and stays defined for 81° either side of its
	 * central meridian, so 60° of margin lands well inside the domain (63° from the
	 * meridian at the very corner) while still covering every rectangle a client can
	 * plausibly ask a German layer about.
	 */
	private static final double MARGIN_DEGREES = 60;

	/** Everything -- the answer for a CRS PROJ has no area of use for, and for EPSG:4326. */
	private static final Window WORLD = new Window(-180, -90, 180, 90);

	private final JdbcClient jdbc;

	private final Map<Integer, Window> windows = new ConcurrentHashMap<>();

	ProjectionDomain(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** A lng/lat rectangle, in EPSG:4326 and in that order -- never x/y of some other CRS. */
	private record Window(double minLng, double minLat, double maxLng, double maxLat) {

		boolean covers(double otherMinLng, double otherMinLat, double otherMaxLng, double otherMaxLat) {
			return otherMinLng >= minLng && otherMaxLng <= maxLng
					&& otherMinLat >= minLat && otherMaxLat <= maxLat;
		}
	}

	/**
	 * Whether a rectangle given in EPSG:4326 can be transformed into {@code srid} and still
	 * describe the same area afterwards.
	 *
	 * <p>Conservative by construction: a {@code false} only ever means "do not rely on the
	 * transform here", never that the transform would certainly fail. A caller that gets
	 * {@code false} pays with a slower query, never with a wrong answer.
	 */
	public boolean covers(int srid, double minLng, double minLat, double maxLng, double maxLat) {
		return windowFor(srid).covers(minLng, minLat, maxLng, maxLat);
	}

	private Window windowFor(int srid) {
		return windows.computeIfAbsent(srid, this::readWindow);
	}

	/**
	 * The area of use PROJ records for the CRS, widened and clamped to the globe.
	 *
	 * <p>{@code postgis_srs} reads PROJ's own database, so it knows nothing about SRIDs that
	 * only exist in {@code spatial_ref_sys} -- but neither does {@code ST_Transform}, which
	 * fails outright for those. Reporting the whole world for them therefore changes
	 * nothing: the transform is no more and no less broken than it was before.
	 *
	 * <p>An area of use that crosses the antimeridian arrives with its west corner east of
	 * its east corner. Rather than teach every caller about a wrapped window, such a CRS is
	 * reported as the whole world, which keeps callers on their fast path -- the same place
	 * they were before this class existed.
	 */
	private Window readWindow(int srid) {
		return jdbc.sql("""
				SELECT ST_X(s.point_sw) AS min_lng, ST_Y(s.point_sw) AS min_lat,
				       ST_X(s.point_ne) AS max_lng, ST_Y(s.point_ne) AS max_lat
				FROM spatial_ref_sys r
				JOIN LATERAL postgis_srs(r.auth_name, r.auth_srid::text) s ON true
				WHERE r.srid = :srid
				""")
				.param("srid", srid)
				.query((rs, rowNum) -> widen(rs.getDouble("min_lng"), rs.getDouble("min_lat"),
						rs.getDouble("max_lng"), rs.getDouble("max_lat")))
				.optional()
				.orElse(WORLD);
	}

	private static Window widen(double minLng, double minLat, double maxLng, double maxLat) {
		if (minLng > maxLng) {
			return WORLD;
		}
		return new Window(
				Math.max(-180, minLng - MARGIN_DEGREES),
				Math.max(-90, minLat - MARGIN_DEGREES),
				Math.min(180, maxLng + MARGIN_DEGREES),
				Math.min(90, maxLat + MARGIN_DEGREES));
	}
}
