package de.kreuter.hgis.places;

import de.kreuter.hgis.places.dto.PlaceDtos;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The Hamburg half of a place search: an {@code ILIKE} substring match over the whole
 * local {@code place} table, accelerated by V10__place.sql's trigram GIN index, ordered by
 * {@code similarity()} within the source -- CONTRACT.md: "Innerhalb einer Quelle nach Güte
 * der Übereinstimmung".
 *
 * <p>{@code ILIKE '%term%'} rather than pg_trgm's own {@code %} similarity operator: that
 * operator rejects anything below {@code pg_trgm.similarity_threshold} (0.3 by default) on
 * the *whole-string* similarity, which a short fragment against a long name fails easily --
 * exactly the case this table exists to fix. CONTRACT.md's own acceptance test is
 * {@code "Hauptstra"} finding "Billstedter Hauptstraße": that substring's similarity to the
 * full name is well under 0.3, so the {@code %} operator would silently drop it while
 * {@code ILIKE} still finds it. {@code similarity()} is used only for ordering the matches
 * {@code ILIKE} already found, never for filtering them out.
 */
@Component
class HamburgPlaceQuery {

	private static final String SQL = """
			SELECT name, context, kind, ST_X(geom) AS lng, ST_Y(geom) AS lat,
			       similarity(gis_meta.place_search_key(name), gis_meta.place_search_key(:term)) AS sim
			FROM gis_meta.place
			WHERE gis_meta.place_search_key(name) ILIKE gis_meta.place_search_key(:pattern) ESCAPE '\\'
			ORDER BY sim DESC, name
			LIMIT :limit
			""";

	private final JdbcClient jdbc;

	HamburgPlaceQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return up to {@code limit} Hamburg hits, best match first; empty if the table is
	 *  empty (no refresh has run yet) or nothing matches -- neither is an error */
	List<PlaceDtos.Result> search(String term, int limit) {
		if (limit <= 0) {
			return List.of();
		}
		String pattern = "%" + escapeWildcards(term) + "%";
		return jdbc.sql(SQL)
				.param("term", term)
				.param("pattern", pattern)
				.param("limit", limit)
				.query((rs, rowNum) -> new PlaceDtos.Result(
						rs.getString("name"),
						rs.getString("context"),
						rs.getDouble("lng"),
						rs.getDouble("lat"),
						"hamburg",
						rs.getString("kind")))
				.list();
	}

	/**
	 * {@code %} and {@code _} are LIKE wildcards; a search for a term that happens to
	 * contain either must match it literally. Same escaping {@code features.TextSearch}
	 * applies to its own ILIKE clause -- backslash first, so escaping {@code %}/{@code _}
	 * does not re-escape the backslashes just added.
	 */
	private static String escapeWildcards(String raw) {
		return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
