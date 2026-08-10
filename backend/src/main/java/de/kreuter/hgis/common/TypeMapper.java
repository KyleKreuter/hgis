package de.kreuter.hgis.common;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps a {@link de.kreuter.hgis.ingest.spi.SourceField#javaType()} to the PostgreSQL type
 * written into the {@code CREATE TABLE} DDL for a layer column.
 *
 * Order matters: {@code java.sql.Date} and {@code java.sql.Time} both extend
 * {@code java.util.Date} and must be checked before the generic case, or every date would
 * be mapped as a timestamp.
 */
public final class TypeMapper {

	private TypeMapper() {
	}

	public static String toPostgresType(Class<?> javaType) {
		if (javaType == null) {
			return "text";
		}
		if (String.class.isAssignableFrom(javaType)) {
			return "text";
		}
		if (byte[].class.isAssignableFrom(javaType)) {
			return "bytea";
		}
		if (java.sql.Date.class.isAssignableFrom(javaType)) {
			return "date";
		}
		if (java.sql.Time.class.isAssignableFrom(javaType)) {
			return "time";
		}
		if (java.util.Date.class.isAssignableFrom(javaType) || Instant.class.isAssignableFrom(javaType)) {
			return "timestamptz";
		}
		if (Byte.class.isAssignableFrom(javaType) || Short.class.isAssignableFrom(javaType)
				|| Integer.class.isAssignableFrom(javaType)) {
			return "integer";
		}
		if (Long.class.isAssignableFrom(javaType) || BigInteger.class.isAssignableFrom(javaType)) {
			return "bigint";
		}
		if (Float.class.isAssignableFrom(javaType) || Double.class.isAssignableFrom(javaType)) {
			return "double precision";
		}
		if (BigDecimal.class.isAssignableFrom(javaType)) {
			return "numeric";
		}
		if (Boolean.class.isAssignableFrom(javaType)) {
			return "boolean";
		}
		if (UUID.class.isAssignableFrom(javaType)) {
			return "uuid";
		}
		return "text";
	}
}
