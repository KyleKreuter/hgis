package de.kreuter.hgis.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TypeMapperTest {

	static Stream<Arguments> mappings() {
		return Stream.of(
				Arguments.of(String.class, "text"),
				Arguments.of(Byte.class, "integer"),
				Arguments.of(Short.class, "integer"),
				Arguments.of(Integer.class, "integer"),
				Arguments.of(Long.class, "bigint"),
				Arguments.of(BigInteger.class, "bigint"),
				Arguments.of(Float.class, "double precision"),
				Arguments.of(Double.class, "double precision"),
				Arguments.of(BigDecimal.class, "numeric"),
				Arguments.of(Boolean.class, "boolean"),
				Arguments.of(java.sql.Date.class, "date"),
				Arguments.of(java.util.Date.class, "timestamptz"),
				Arguments.of(java.sql.Timestamp.class, "timestamptz"),
				Arguments.of(Instant.class, "timestamptz"),
				Arguments.of(java.sql.Time.class, "time"),
				Arguments.of(byte[].class, "bytea"),
				Arguments.of(UUID.class, "uuid"),
				Arguments.of(Object.class, "text"));
	}

	@ParameterizedTest
	@MethodSource("mappings")
	@DisplayName("maps Java attribute types to their PostgreSQL DDL type")
	void mapsJavaTypesToPostgresTypes(Class<?> javaType, String expected) {
		assertThat(TypeMapper.toPostgresType(javaType)).isEqualTo(expected);
	}

	@Test
	void treatsNullAsText() {
		assertThat(TypeMapper.toPostgresType(null)).isEqualTo("text");
	}
}
