package de.kreuter.hgis.common;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The single place allowed to turn a name into a SQL identifier.
 *
 * Layer tables are created at runtime, so their names and column names end up inside
 * DDL and DML that cannot use bind parameters -- identifiers are simply not bindable.
 * Every such string passes through here: normalised, length-limited, validated against
 * a strict pattern and finally quoted. Values never come this way; they always go
 * through bind parameters.
 */
public final class SqlIdentifier {

	/** PostgreSQL truncates identifiers beyond this, which would silently create collisions. */
	public static final int MAX_LENGTH = 63;

	/** Occupied by every layer table, so a source column of the same name must yield. */
	private static final Set<String> RESERVED = Set.of("fid", "geom");

	private static final Pattern SAFE_COLUMN = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
	private static final Pattern SAFE_TABLE = Pattern.compile("^layer_[0-9a-f]{32}$");
	private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9_]+");
	private static final Pattern REPEATED_UNDERSCORE = Pattern.compile("_{2,}");

	private SqlIdentifier() {
	}

	/**
	 * Table name for a layer: always 'layer_' + the hex digits of its id. Derived purely
	 * from a UUID we generated, so user input can never reach a table name.
	 */
	public static String tableName(UUID layerId) {
		return "layer_" + layerId.toString().replace("-", "").toLowerCase(Locale.ROOT);
	}

	/**
	 * Turns a source attribute name into a safe, unique column name.
	 *
	 * @param sourceName raw name from the file, may contain umlauts, spaces, anything
	 * @param taken      column names already assigned for this layer; the result is
	 *                   guaranteed not to be among them
	 */
	public static String toColumnName(String sourceName, Collection<String> taken) {
		String candidate = transliterate(sourceName == null ? "" : sourceName)
				.toLowerCase(Locale.ROOT);

		candidate = NON_WORD.matcher(candidate).replaceAll("_");
		candidate = REPEATED_UNDERSCORE.matcher(candidate).replaceAll("_");
		candidate = trimUnderscores(candidate);

		if (candidate.isEmpty()) {
			candidate = "col";
		}
		// Identifiers may not start with a digit.
		if (Character.isDigit(candidate.charAt(0))) {
			candidate = "c_" + candidate;
		}
		candidate = truncate(candidate, MAX_LENGTH);

		if (RESERVED.contains(candidate) || taken.contains(candidate)) {
			candidate = makeUnique(candidate, taken);
		}
		return candidate;
	}

	/**
	 * Quotes a column name for use in SQL, rejecting anything that does not match the
	 * expected shape. The check is redundant after {@link #toColumnName} -- and that is
	 * the point: it also catches names that reached the database by some other route.
	 */
	public static String quoteColumn(String columnName) {
		if (columnName == null || !SAFE_COLUMN.matcher(columnName).matches()) {
			throw new IllegalArgumentException("Unsafe column identifier: " + columnName);
		}
		return '"' + columnName + '"';
	}

	/** Quotes a layer table name, schema-qualified. Rejects anything else. */
	public static String quoteLayerTable(String tableName) {
		if (tableName == null || !SAFE_TABLE.matcher(tableName).matches()) {
			throw new IllegalArgumentException("Unsafe table identifier: " + tableName);
		}
		return "gis_data.\"" + tableName + '"';
	}

	public static boolean isValidColumn(String columnName) {
		return columnName != null && SAFE_COLUMN.matcher(columnName).matches();
	}

	public static boolean isValidLayerTable(String tableName) {
		return tableName != null && SAFE_TABLE.matcher(tableName).matches();
	}

	// --- internals -------------------------------------------------------------

	/**
	 * German umlauts become their two-letter forms; other diacritics are stripped.
	 * ASCII is correct here because the result is a SQL identifier, not display text --
	 * the original name is preserved in layer_field.source_name and shown in the UI.
	 */
	private static String transliterate(String value) {
		String replaced = value
				.replace("ä", "ae").replace("Ä", "Ae")
				.replace("ö", "oe").replace("Ö", "Oe")
				.replace("ü", "ue").replace("Ü", "Ue")
				.replace("ß", "ss");
		// Decompose remaining accented characters and drop the combining marks.
		return Normalizer.normalize(replaced, Normalizer.Form.NFD)
				.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
	}

	private static String makeUnique(String base, Collection<String> taken) {
		for (int suffix = 1; suffix < 10_000; suffix++) {
			String tail = "_" + suffix;
			// Truncate the base, not the suffix, so uniqueness survives the length limit.
			String candidate = truncate(base, MAX_LENGTH - tail.length()) + tail;
			if (!RESERVED.contains(candidate) && !taken.contains(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not derive a unique column name from: " + base);
	}

	private static String truncate(String value, int max) {
		return value.length() <= max ? value : trimUnderscores(value.substring(0, max));
	}

	private static String trimUnderscores(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && value.charAt(start) == '_') {
			start++;
		}
		while (end > start && value.charAt(end - 1) == '_') {
			end--;
		}
		return value.substring(start, end);
	}
}
