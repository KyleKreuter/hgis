package de.kreuter.hgis.catalog;

import de.kreuter.hgis.common.BadRequestException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a field name the client sent into the {@code layer_field} row that describes it.
 *
 * <p>Same rule as the FilterParser, and for the same reason: a client never names a SQL
 * identifier. It names a field, this lookup decides whether that field exists, and only
 * the {@code column_name} it returns is ever allowed near a query. A name that does not
 * resolve is rejected -- there is no fallback that passes the text through.
 *
 * <p>Both spellings are accepted, case-insensitively: the source name shown in the UI and
 * the normalised column name that appears as a key in feature responses and tile
 * properties. Source names win on a collision, matching sort and filter.
 */
final class LayerFields {

	/** PostgreSQL types a classification can be computed over. */
	private static final Set<String> NUMERIC_TYPES = Set.of(
			"smallint", "integer", "bigint", "real", "double precision", "numeric", "decimal");

	private LayerFields() {
	}

	static Optional<LayerField> find(String name, List<LayerField> fields) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		String wanted = name.trim().toLowerCase(Locale.ROOT);
		return fields.stream()
				.filter(field -> field.getSourceName().toLowerCase(Locale.ROOT).equals(wanted))
				.findFirst()
				.or(() -> fields.stream()
						.filter(field -> field.getColumnName().toLowerCase(Locale.ROOT).equals(wanted))
						.findFirst());
	}

	static LayerField require(String name, List<LayerField> fields) {
		return find(name, fields).orElseThrow(() -> new BadRequestException(
				"Unbekanntes Feld: " + name + ". Verfügbar: " + String.join(", ", sourceNames(fields))));
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

	private static List<String> sourceNames(List<LayerField> fields) {
		return fields.stream().map(LayerField::getSourceName).toList();
	}
}
