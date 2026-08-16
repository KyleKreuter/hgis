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
 * <p>Three spellings are accepted, case-insensitively: the source name shown in the UI, the
 * normalised column name that appears as a key in feature responses and tile properties,
 * and the field id.
 *
 * <p>The first two can collide. The Straßenbaumkataster has a field named
 * "Kronendurchmesser" whose column is {@code kronendurchmesser_z}, next to a field
 * "Kronendurchmesser Quelle" whose column is {@code kronendurchmesser}, so the word
 * "kronendurchmesser" means both. Such a name is <em>rejected</em>, never resolved to
 * whichever candidate happens to come first: filtering and sorting used to pick opposite
 * ones, so the same name silently meant a text column in one place and a bigint column in
 * the other, and both answers looked like an answer.
 *
 * <p>The id is the third spelling because of that rejection. Display names collide and
 * column names collide with display names, but an id is unique for the life of the field
 * and survives a rename -- it is the only identifier that always resolves, which is what
 * lets a client address the two fields above at all. The ambiguity message therefore prints
 * it for every candidate.
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
	 * Whether this name, on its own, means exactly one field of the layer.
	 *
	 * <p>Same lookup as {@link #require}, asked as a question instead of as a demand. It is
	 * what lets a message spend a field id only where the name does not carry: naming a field
	 * is the short answer, and the id is the one that survives a collision.
	 */
	public static boolean resolvesUniquely(String name, List<LayerField> fields) {
		return matching(name, fields).size() == 1;
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

	/**
	 * Whether a comparison or a computation may read this field as a quantity.
	 *
	 * <p>Public because {@code FilterParser} names the numeric fields of a layer when it
	 * refuses to order a text column against a number, and that list has to mean the same
	 * thing there as it does for a classification. One definition, one place.
	 */
	public static boolean isNumeric(LayerField field) {
		return NUMERIC_TYPES.contains(baseType(field));
	}

	/** Strips a length or precision suffix: "numeric(10,2)" -> "numeric". */
	static String baseType(LayerField field) {
		String type = field.getDataType().toLowerCase(Locale.ROOT);
		int parenthesis = type.indexOf('(');
		return parenthesis < 0 ? type : type.substring(0, parenthesis);
	}

	/**
	 * Every field the name matches, by field id, display name or column name.
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
		return wanted.equals(idOf(field))
				|| field.getSourceName().toLowerCase(Locale.ROOT).equals(wanted)
				|| field.getColumnName().toLowerCase(Locale.ROOT).equals(wanted);
	}

	/**
	 * The field id as it may be written, or null when the field has none.
	 *
	 * <p>Null happens twice: before a field is persisted, and for the synthetic {@code fid}
	 * of {@code QueryFields}, which describes a column we create rather than a
	 * {@code layer_field} row. Neither can be addressed by id, and neither may be matched
	 * by a client that sends the word "null".
	 *
	 * <p>{@link java.util.UUID#toString} is lower case, so comparing against the
	 * already-lowered name needs no further folding.
	 */
	private static String idOf(LayerField field) {
		return field.getId() == null ? null : field.getId().toString();
	}

	/**
	 * Names the candidates and how to reach each of them.
	 *
	 * <p>What makes the message actionable is that every way out in it was checked against
	 * the same lookup that just failed: the "Eindeutig sind" list holds only names that
	 * really do match one field, and the id printed with each candidate is unique by
	 * construction. Whoever wrote the name -- a person or a program reading the error --
	 * can take one from it and write the next expression correctly.
	 *
	 * <p>Two fields can leave each other without a usable name (each one's column name is
	 * the other's display name, reachable by renaming). The id is the way out that survives
	 * that, which is why it is printed for every candidate rather than only when the names
	 * run out.
	 */
	private static String ambiguity(String name, String kind, List<LayerField> matches,
			List<LayerField> fields) {
		String candidates = matches.stream()
				.map(LayerFields::describe)
				.reduce((left, right) -> left + ", " + right)
				.orElse("");
		List<String> unique = matches.stream()
				.flatMap(field -> Stream.of(field.getSourceName(), field.getColumnName()))
				.distinct()
				.filter(candidate -> matching(candidate, fields).size() == 1)
				.toList();
		boolean addressableById = matches.stream().allMatch(field -> idOf(field) != null);

		String message = "Mehrdeutiges " + kind + ": " + name + ". Der Name passt auf "
				+ matches.size() + " Felder: " + candidates + ".";
		if (!unique.isEmpty()) {
			message += " Eindeutig sind: " + String.join(", ", unique) + ".";
		}
		else {
			message += " Kein Name spricht genau eines dieser Felder an.";
		}
		return addressableById ? message + " Die Id trifft immer genau ein Feld." : message;
	}

	/** One candidate with every way to reach it. The id is left out when there is none. */
	private static String describe(LayerField field) {
		String id = idOf(field);
		return field.getSourceName() + " (Spalte " + field.getColumnName()
				+ (id == null ? "" : ", Id " + id) + ")";
	}

	private static List<String> sourceNames(List<LayerField> fields) {
		return fields.stream().map(LayerField::getSourceName).toList();
	}
}
