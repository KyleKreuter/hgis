package de.kreuter.hgis.common;

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
}
