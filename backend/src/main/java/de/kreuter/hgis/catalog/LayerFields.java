package de.kreuter.hgis.catalog;

import de.kreuter.hgis.common.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resolves a field name the client sent into the {@code layer_field} row that describes it.
 *
 * <p>A client never names a SQL identifier. It names a field, this lookup decides whether
 * that field exists, and only the {@code column_name} it returns is ever allowed near a
 * query. A name that does not resolve is rejected -- there is no fallback that passes the
 * text through.
 *
 * <p>Both spellings are accepted, case-insensitively: the source name shown in the UI and
 * the normalised column name that appears as a key in feature responses and tile
 * properties. Because both are accepted, one name can mean two different fields -- the
 * Straßenbaumkataster has a field named "Kronendurchmesser" whose column is
 * {@code kronendurchmesser_z}, next to a field "Kronendurchmesser Quelle" whose column is
 * {@code kronendurchmesser}. Such a name is <em>rejected</em>, never resolved to whichever
 * candidate happens to come first: filtering and sorting used to pick opposite ones, so the
 * same name silently meant a text column in one place and a bigint column in the other, and
 * both answers looked like an answer.
 *
 * <p>This is the only rule for a name a client sent. Filter expressions, the sort
 * parameter, classifications and style fields all come through here, so none of them can
 * drift away from the others.
 */
public final class LayerFields {

	/** PostgreSQL types a classification can be computed over. */
	private static final Set<String> NUMERIC_TYPES = Set.of(
			"smallint", "integer", "bigint", "real", "double precision", "numeric", "decimal");

	private LayerFields() {
	}

	/**
	 * The one field this name means.
	 *
	 * @param kind what the name was given as, as it appears in the messages: "Feld" for a
	 *     filter or a style, "Sortierfeld" for the sort parameter. Both wordings are read
	 *     by the client -- {@code frontend/src/table/filterValidity.ts} matches on
	 *     "Unbekanntes Feld" and {@code frontend/src/table/sortValidity.ts} on "Unbekanntes
	 *     Sortierfeld", each to drop the one setting that points at a deleted field. The
	 *     two must stay different strings, and neither may appear in the ambiguity message
	 *     below: an ambiguous name is not a stale setting the client may silently discard,
	 *     it is a question only the user can answer.
	 * @throws BadRequestException when no field carries the name, or when more than one does
	 */
	public static LayerField require(String name, List<LayerField> fields, String kind) {
		List<LayerField> matches = matching(name, fields);
		if (matches.isEmpty()) {
			throw new BadRequestException("Unbekanntes " + kind + ": " + name
					+ ". Verfügbar: " + String.join(", ", sourceNames(fields)) + ".");
		}
		if (matches.size() > 1) {
			throw new BadRequestException(ambiguity(name, kind, matches, fields));
		}
		return matches.get(0);
	}

	/** As {@link #require(String, List, String)}, for a name given as a plain field. */
	public static LayerField require(String name, List<LayerField> fields) {
		return require(name, fields, "Feld");
	}

	/**
	 * The field a stored style names.
	 *
	 * <p>By column name only, and deliberately not through {@link #require}: a style is
	 * validated when it is written and keeps the resolved {@code column_name} from then on
	 * (see {@code LayerStyleService.validateRenderer}). Reading it back through the
	 * client-facing rule would let a column name that is also another field's display name
	 * resolve to that other field -- the tile would then be drawn from the wrong column.
	 *
	 * <p>Empty rather than an exception, because the only caller runs on the tile path: a
	 * style that no longer matches its layer must degrade to the plain tile, not turn every
	 * tile request into a 500.
	 */
	static Optional<LayerField> byColumnName(String columnName, List<LayerField> fields) {
		if (columnName == null || columnName.isBlank()) {
			return Optional.empty();
		}
		return fields.stream()
				.filter(field -> field.getColumnName().equals(columnName))
				.findFirst();
	}

	static boolean isNumeric(LayerField field) {
		return NUMERIC_TYPES.contains(baseType(field));
	}

	/** Strips a length or precision suffix: "numeric(10,2)" -> "numeric". */
	static String baseType(LayerField field) {
		String type = field.getDataType().toLowerCase(Locale.ROOT);
		int parenthesis = type.indexOf('(');
		return parenthesis < 0 ? type : type.substring(0, parenthesis);
	}

	/**
	 * Every field the name matches, by display name or by column name.
	 *
	 * <p>One pass, one entry per field: a field whose display name equals its own column
	 * name -- the ordinary case -- is one candidate, not two.
	 */
	private static List<LayerField> matching(String name, List<LayerField> fields) {
		if (name == null || name.isBlank()) {
			return List.of();
		}
		String wanted = name.trim().toLowerCase(Locale.ROOT);
		List<LayerField> matches = new ArrayList<>(2);
		for (LayerField field : fields) {
			if (carriesName(field, wanted)) {
				matches.add(field);
			}
		}
		return matches;
	}

	private static boolean carriesName(LayerField field, String wanted) {
		return field.getSourceName().toLowerCase(Locale.ROOT).equals(wanted)
				|| field.getColumnName().toLowerCase(Locale.ROOT).equals(wanted);
	}

	/**
	 * Names the candidates and how to reach each of them.
	 *
	 * <p>The list of unambiguous names is what makes the message actionable: whoever wrote
	 * the name -- a person or a program reading the error -- can take one from it and write
	 * the next expression correctly. A field can be left without any such name (two fields
	 * sharing a display name, where each column name is the other's display name), and the
	 * message then says so instead of offering an alternative that fails the same way.
	 */
	private static String ambiguity(String name, String kind, List<LayerField> matches,
			List<LayerField> fields) {
		String candidates = matches.stream()
				.map(field -> field.getSourceName() + " (Spalte " + field.getColumnName() + ")")
				.reduce((left, right) -> left + ", " + right)
				.orElse("");
		List<String> unique = matches.stream()
				.flatMap(field -> Stream.of(field.getSourceName(), field.getColumnName()))
				.distinct()
				.filter(candidate -> matching(candidate, fields).size() == 1)
				.toList();

		String message = "Mehrdeutiges " + kind + ": " + name + ". Der Name passt auf "
				+ matches.size() + " Felder: " + candidates + ".";
		return unique.isEmpty()
				? message + " Kein Name spricht genau eines dieser Felder an."
						+ " Benennen Sie eines der Felder um."
				: message + " Eindeutig sind: " + String.join(", ", unique) + ".";
	}

	private static List<String> sourceNames(List<LayerField> fields) {
		return fields.stream().map(LayerField::getSourceName).toList();
	}
}
