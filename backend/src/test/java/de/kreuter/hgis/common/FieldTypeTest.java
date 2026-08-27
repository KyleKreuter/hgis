package de.kreuter.hgis.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FieldTypeTest {

	static Stream<Arguments> mappings() {
		return Stream.of(
				Arguments.of(FieldType.TEXT, "text"),
				Arguments.of(FieldType.INTEGER, "integer"),
				Arguments.of(FieldType.BIGINT, "bigint"),
				Arguments.of(FieldType.DOUBLE, "double precision"),
				Arguments.of(FieldType.NUMERIC, "numeric"),
				Arguments.of(FieldType.BOOLEAN, "boolean"),
				Arguments.of(FieldType.DATE, "date"),
				Arguments.of(FieldType.TIME, "time"),
				Arguments.of(FieldType.TIMESTAMP, "timestamptz"));
	}

	@ParameterizedTest
	@MethodSource("mappings")
	@DisplayName("every token carries exactly the PostgreSQL type the CONTRACT fixes for it")
	void mapsEachTokenToItsFixedPostgresType(FieldType type, String expected) {
		assertThat(type.pgType()).isEqualTo(expected);
	}

	@Test
	@DisplayName("uuid and bytea are deliberately not part of this enum")
	void hasExactlyNineValues() {
		assertThat(FieldType.values()).hasSize(9);
	}

	static Stream<Arguments> tokenSpellings() {
		return Stream.of(
				// The constant name, as create_layer always accepted.
				Arguments.of("TEXT", FieldType.TEXT),
				// Befund 2 (Validierung, 27.08.): describe_layer reports an existing
				// field's type lower-case -- the exact spelling that used to be rejected.
				Arguments.of("text", FieldType.TEXT),
				Arguments.of("bigint", FieldType.BIGINT),
				// Mixed case is no more special than all-lower or all-upper.
				Arguments.of("BigInt", FieldType.BIGINT),
				// describe_layer's pgType spelling differs from the constant name outright
				// for these two -- fromToken has to resolve both, not just case.
				Arguments.of("double precision", FieldType.DOUBLE),
				Arguments.of("DOUBLE PRECISION", FieldType.DOUBLE),
				Arguments.of("timestamptz", FieldType.TIMESTAMP));
	}

	@ParameterizedTest
	@MethodSource("tokenSpellings")
	@DisplayName("fromToken resolves the constant name and the pgType spelling alike, case-insensitively")
	void fromTokenResolvesEitherSpellingRegardlessOfCase(String raw, FieldType expected) {
		assertThat(FieldType.fromToken(raw)).isEqualTo(expected);
	}

	@Test
	@DisplayName("fromToken rejects an unknown token and names every valid constant")
	void fromTokenRejectsAnUnknownTokenWithTheValidValues() {
		assertThatThrownBy(() -> FieldType.fromToken("varchar"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Unbekannter Feldtyp: varchar. Gültig sind "
						+ "TEXT, INTEGER, BIGINT, DOUBLE, NUMERIC, BOOLEAN, DATE, TIME, TIMESTAMP.");
	}
}
