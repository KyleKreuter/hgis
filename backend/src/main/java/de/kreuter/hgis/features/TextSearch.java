package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a free-text search term into an ILIKE condition across every text field of a
 * layer -- the syntax-free counterpart to {@link FilterParser}. Where the filter
 * expression lets a client name exactly which field to compare, a search term is meant
 * to need no knowledge of field names at all: it is checked against all of them.
 *
 * <p>{@code %} and {@code _} are LIKE wildcards. A search for {@code 50%} or {@code A_1}
 * must match that string literally, not act as a pattern -- otherwise "50%" would match
 * any value starting with "50", silently returning more than what was typed. Both
 * characters are escaped with a backslash before the term is wrapped in its own leading
 * and trailing {@code %}, and the generated clause spells out {@code ESCAPE '\'} so
 * PostgreSQL treats that same backslash as the escape character rather than a literal one.
 */
final class TextSearch {

	private TextSearch() {
	}

	/**
	 * @param search user-supplied term; blank (including {@code null}) means "no search"
	 * @param fields the layer's fields; only columns of type {@code text} are searched
	 * @return the ILIKE fragment across every text field, bound as a single parameter, or
	 *     {@code null} when nothing was searched
	 * @throws BadRequestException when the layer has no text field to search in -- an
	 *     empty result would otherwise look identical to "searched, found nothing"
	 */
	static FilterParser.ParsedFilter parse(String search, List<LayerField> fields) {
		if (search == null || search.isBlank()) {
			return null;
		}

		List<LayerField> textFields = fields.stream()
				.filter(field -> field.getDataType().equalsIgnoreCase("text"))
				.toList();
		if (textFields.isEmpty()) {
			throw new BadRequestException(
					"Dieser Layer hat keine Textfelder, in denen gesucht werden könnte");
		}

		List<String> conditions = new ArrayList<>();
		for (LayerField field : textFields) {
			conditions.add(SqlIdentifier.quoteColumn(field.getColumnName())
					+ " ILIKE :searchTerm ESCAPE '\\'");
		}

		// One bound value, referenced by every field's condition -- the term is the same
		// pattern regardless of which column it is compared against.
		String pattern = "%" + escapeWildcards(search.trim()) + "%";
		return new FilterParser.ParsedFilter(
				"(" + String.join(" OR ", conditions) + ")", Map.of("searchTerm", pattern));
	}

	/** Backslash first, so escaping % and _ does not re-escape the backslashes just added. */
	private static String escapeWildcards(String raw) {
		return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
