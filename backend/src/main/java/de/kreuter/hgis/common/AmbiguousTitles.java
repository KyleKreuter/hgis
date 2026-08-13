package de.kreuter.hgis.common;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one rule this program follows when two things that are shown together end up with
 * the same display name: the first occurrence keeps the name it was given, the second and
 * every later one is qualified. A name that occurs once is therefore never dressed up --
 * qualifying every occurrence would make the common case, a unique name, harder to read in
 * order to solve a problem it does not have.
 *
 * <p>Two places need it and must decide it identically, which is why the rule lives here
 * once instead of twice: the fields of one collection (CONTRACT.md 11.4, {@code
 * QueryablesSchema}, which falls back to the technical name) and the collections of the
 * Geoportal catalog (CONTRACT.md 11.9, {@code CatalogLoader}, which prefixes the service
 * name). What differs between them is only <em>how</em> a repeat is qualified;
 * <em>which</em> occurrences are repeats is this class.
 *
 * <p>Deliberately only reports the repeats rather than rewriting anything: the two callers
 * hold different types, and a shared rewrite would need either a common supertype neither
 * has any other use for, or generics that hide a rule this short.
 */
public final class AmbiguousTitles {

	private AmbiguousTitles() {
	}

	/**
	 * Compares case-insensitively: two titles that differ only in case read as the same
	 * name in a list and would leave the reader with no way to tell the rows apart.
	 *
	 * @param titles the display names, in the order they are shown -- the order is what
	 *               decides which occurrence is "the first" and keeps its name
	 * @return one flag per title, {@code true} exactly where the same title already
	 *         occurred earlier in {@code titles}
	 */
	public static boolean[] repeats(List<String> titles) {
		Set<String> seen = new HashSet<>();
		boolean[] repeats = new boolean[titles.size()];
		for (int i = 0; i < titles.size(); i++) {
			String title = titles.get(i);
			repeats[i] = !seen.add(title == null ? "" : title.toLowerCase(Locale.ROOT));
		}
		return repeats;
	}
}
