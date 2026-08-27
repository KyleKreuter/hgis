package de.kreuter.hgis.common;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Attribute types a user may pick when creating a layer field by hand, as opposed to
 * {@link TypeMapper}, which infers a PostgreSQL type from a Java class during import.
 *
 * <p>Deliberately closed, and deliberately smaller than what {@code layer_field.data_type}
 * can actually hold: that column has no CHECK constraint in the database, so this enum is
 * the only thing standing between a client-supplied token and a raw string reaching DDL --
 * the PostgreSQL type for a hand-created field must always come from {@link #pgType()},
 * never from the request directly.
 *
 * <p>{@code uuid} and {@code bytea} are left out on purpose. Both are read-only in the
 * attribute editor, so a field a user could create but never fill in would be a trap, not
 * a feature.
 */
public enum FieldType {
	TEXT("text"),
	INTEGER("integer"),
	BIGINT("bigint"),
	DOUBLE("double precision"),
	NUMERIC("numeric"),
	BOOLEAN("boolean"),
	DATE("date"),
	TIME("time"),
	TIMESTAMP("timestamptz");

	private final String pgType;

	FieldType(String pgType) {
		this.pgType = pgType;
	}

	/** The PostgreSQL type written into the {@code CREATE TABLE} DDL for a column of this type. */
	public String pgType() {
		return pgType;
	}

	/**
	 * Every spelling {@link #fromToken} accepts for one constant, upper-cased: the
	 * constant's own name ({@code "TEXT"}), and its {@link #pgType} ({@code "DOUBLE
	 * PRECISION"}, {@code "TIMESTAMPTZ"}, ...) -- the exact token {@code describe_layer}
	 * hands back for an existing field of that type (Befund 2, 27.08.). Whichever of the
	 * two happens to equal the other for a given constant collapses to one map entry;
	 * nothing here depends on them staying distinct.
	 */
	private static final Map<String, FieldType> BY_TOKEN = buildTokenIndex();

	private static Map<String, FieldType> buildTokenIndex() {
		Map<String, FieldType> index = new HashMap<>();
		for (FieldType type : values()) {
			index.put(type.name().toUpperCase(Locale.ROOT), type);
			index.put(type.pgType().toUpperCase(Locale.ROOT), type);
		}
		return index;
	}

	/**
	 * Resolves a client-supplied type token case-insensitively, and against either the
	 * constant's own name or the PostgreSQL type it maps to.
	 *
	 * <p>Befund 2 (Validierung, 27.08.): {@code describe_layer} reports an existing
	 * field's type as PostgreSQL spells it -- lower-case, {@code "double precision"} for
	 * {@link #DOUBLE}, {@code "timestamptz"} for {@link #TIMESTAMP} -- while {@code
	 * create_layer} demanded the upper-case constant name and nothing else. A caller that
	 * read a type off one field and passed it straight to create a new one had no way to
	 * know those were different spellings of the same thing. The type this enum actually
	 * writes to DDL never depends on which spelling a caller used -- {@link #pgType()}
	 * only ever reads off the resolved constant -- so relaxing which token reaches it here
	 * costs nothing in what ends up in the database.
	 *
	 * @throws IllegalArgumentException with {@link #unknownTypeMessage(String)} when
	 *     {@code raw} matches neither a constant name nor a {@link #pgType} value
	 */
	public static FieldType fromToken(String raw) {
		FieldType type = raw == null ? null : BY_TOKEN.get(raw.toUpperCase(Locale.ROOT));
		if (type == null) {
			throw new IllegalArgumentException(unknownTypeMessage(raw));
		}
		return type;
	}

	/**
	 * The message for a token that matches neither a constant name nor a {@link #pgType}
	 * value -- names every valid one by its constant name, the same {@code
	 * LayerFields.require} pattern an unknown field name already follows (Aufgabe 18): a
	 * caller that got the token wrong can act on this reply without a second guess.
	 */
	public static String unknownTypeMessage(String raw) {
		return "Unbekannter Feldtyp: " + raw + ". Gültig sind "
				+ Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", ")) + ".";
	}
}
