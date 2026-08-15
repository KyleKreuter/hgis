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
 *
 * <p>House numbers are searched only when the term contains a digit -- the one design
 * decision the house-number contract calls out as the important one. There are 302393
 * addresses against 9936 streets and districts, so a term without a digit that matched
 * addresses too would bury what the person is looking for: "Hauptstra" would answer with
 * hundreds of house numbers in Hauptstraße instead of the handful of Hauptstraßen. Nobody
 * types a street name meaning to get its house numbers; a digit is what says otherwise, and
 * it is the only signal in a search box that does.
 */
@Component
class HamburgPlaceQuery {

	private static final String SELECT = """
			SELECT name, context, kind, ST_X(geom) AS lng, ST_Y(geom) AS lat,
			       similarity(gis_meta.place_search_key(name), gis_meta.place_search_key(:term)) AS sim
			FROM gis_meta.place
			WHERE gis_meta.place_search_key(name) ILIKE gis_meta.place_search_key(:pattern) ESCAPE '\\'
			""";

	private static final String ORDER = """
			ORDER BY sim DESC, name
			LIMIT :limit
			""";

	private static final String SQL_WITH_ADDRESSES = SELECT + ORDER;

	/**
	 * Two whole statements rather than one with a {@code :includeAddresses} flag in its
	 * WHERE clause: a flag would make both cases share one query plan, planned for whichever
	 * of them ran first, and these two want different ones -- with the filter the planner
	 * knows it is looking at 3&nbsp;% of the table, without it at all of it.
	 *
	 * <p>{@code kind <> 'address'} is also what makes V11__place_address.sql's partial index
	 * {@code place_name_trgm_no_address_idx} usable, and that is worth more here than the
	 * plan split: without it a two-character term costs 513&nbsp;ms instead of 30&nbsp;ms
	 * (measured through the API at the full 312329 rows; 30&nbsp;ms is also what the same
	 * term cost before the addresses existed). Postgres does not require the clause to read
	 * exactly like the index predicate -- it proves that the one implies the other, and it
	 * manages that for {@code kind IN ('street', 'district', 'place')} too (verified) -- but
	 * a clause it cannot prove anything about would lose the index silently, with nothing
	 * but the response time to show for it. Package-private so {@code PlaceMigrationTest}
	 * can check the real statement against the real index rather than a copy of it.
	 */
	static final String SQL_WITHOUT_ADDRESSES = SELECT + "  AND kind <> 'address'\n" + ORDER;

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
		return jdbc.sql(containsDigit(term) ? SQL_WITH_ADDRESSES : SQL_WITHOUT_ADDRESSES)
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
	 * Any Unicode digit, not only {@code '0'}-{@code '9'}: {@link Character#isDigit} is what
	 * a person typing on a non-Latin keypad would expect to count, and a term that reaches
	 * here has already been through nothing that would normalise it.
	 */
	private static boolean containsDigit(String term) {
		return term.codePoints().anyMatch(Character::isDigit);
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
