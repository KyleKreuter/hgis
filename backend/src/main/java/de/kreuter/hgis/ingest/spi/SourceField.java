package de.kreuter.hgis.ingest.spi;

/**
 * One attribute of a source file.
 *
 * The reader reports the Java type it will deliver; mapping that to a PostgreSQL type
 * and deriving a safe column name is the writing side's job. Keeping the two apart is
 * what lets both be developed and tested independently.
 *
 * @param name     original name from the file, umlauts and spaces included
 * @param javaType type of the values that {@link SourceFeature#attributes()} will carry
 *                 for this field; null values are permitted regardless
 */
public record SourceField(String name, Class<?> javaType) {
}
