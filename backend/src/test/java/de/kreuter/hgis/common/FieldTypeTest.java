package de.kreuter.hgis.common;

import static org.assertj.core.api.Assertions.assertThat;

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
}
