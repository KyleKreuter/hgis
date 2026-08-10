package de.kreuter.hgis.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.features.FilterParser.ParsedFilter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The filter expression is the only place where user input shapes a query, which makes
 * this the security-critical parser of the application. The injection cases below are
 * the reason it exists at all -- everything else is convenience.
 */
class FilterParserTest {

	private static final List<LayerField> FIELDS = List.of(
			new LayerField(null, "name", "name", "text", 0),
			new LayerField(null, "Gebäudehöhe", "gebaeudehoehe", "double precision", 1),
			new LayerField(null, "einwohner", "einwohner", "bigint", 2),
			new LayerField(null, "bewohnt", "bewohnt", "boolean", 3),
			new LayerField(null, "erfasst_am", "erfasst_am", "timestamptz", 4));

	private static ParsedFilter parse(String expression) {
		return FilterParser.parse(expression, FIELDS);
	}

	@Nested
	@DisplayName("values never reach the SQL fragment")
	class Injection {

		@Test
		void aStatementTerminatorInsideAValueStaysAValue() {
			ParsedFilter filter = parse("name = 'x''; DROP TABLE gis_data.layer_1; --'");

			assertThat(filter.sql()).doesNotContain("DROP", ";");
			assertThat(filter.sql()).isEqualTo("\"name\" = :f0");
			assertThat(filter.parameters()).containsExactly(
					java.util.Map.entry("f0", "x'; DROP TABLE gis_data.layer_1; --"));
		}

		@Test
		void aQuotedIdentifierCannotSmuggleSql() {
			// Even quoted, an identifier has to be a field of this layer -- and the SQL
			// carries the resolved column_name, never the text that was typed.
			assertThatThrownBy(() -> parse("\"name\"; DROP TABLE x; --\" = 1"))
					.isInstanceOf(BadRequestException.class);
		}

		@ParameterizedTest
		@ValueSource(strings = {
				"1=1 OR 1=1",
				"name = name",
				"; DROP TABLE gis_data.layer_1",
				"name = 'a' UNION SELECT * FROM gis_meta.project",
				"pg_sleep(10) > 0",
				"name = (SELECT name FROM gis_meta.project LIMIT 1)",
		})
		void rejectsAnythingThatIsNotTheGrammar(String attempt) {
			assertThatThrownBy(() -> parse(attempt)).isInstanceOf(BadRequestException.class);
		}

		@Test
		void anUnknownFieldIsRejectedAndTheKnownOnesAreNamed() {
			assertThatThrownBy(() -> parse("passwort = 'x'"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Unbekanntes Feld: passwort")
					.hasMessageContaining("Gebäudehöhe");
		}
	}

	@Nested
	@DisplayName("grammar")
	class Grammar {

		@Test
		void comparesAndBindsTheValue() {
			ParsedFilter filter = parse("einwohner >= 1000");

			assertThat(filter.sql()).isEqualTo("\"einwohner\" >= :f0");
			assertThat(filter.parameters()).containsEntry("f0", 1000L);
		}

		@Test
		void combinesWithAndOrAndParentheses() {
			ParsedFilter filter = parse("name = 'A' AND (einwohner > 5 OR bewohnt = true)");

			assertThat(filter.sql())
					.isEqualTo("(\"name\" = :f0 AND (\"einwohner\" > :f1 OR \"bewohnt\" = :f2))");
			assertThat(filter.parameters()).containsValues("A", 5L, true);
		}

		@Test
		void normalisesBangEqualsToSqlInequality() {
			assertThat(parse("name != 'A'").sql()).isEqualTo("\"name\" <> :f0");
		}

		@Test
		void handlesIsNullAndIsNotNull() {
			assertThat(parse("name IS NULL").sql()).isEqualTo("\"name\" IS NULL");
			assertThat(parse("name IS NOT NULL").sql()).isEqualTo("\"name\" IS NOT NULL");
		}

		@Test
		void handlesInLists() {
			ParsedFilter filter = parse("einwohner IN (1, 2, 3)");

			assertThat(filter.sql()).isEqualTo("\"einwohner\" IN (:f0, :f1, :f2)");
			assertThat(filter.parameters()).containsValues(1L, 2L, 3L);
		}

		@Test
		void handlesNotIn() {
			assertThat(parse("name NOT IN ('a')").sql()).isEqualTo("\"name\" NOT IN (:f0)");
		}

		@Test
		void handlesNot() {
			assertThat(parse("NOT name = 'A'").sql()).isEqualTo("NOT (\"name\" = :f0)");
		}

		@Test
		void resolvesAQuotedFieldNameWithUmlautsToItsNormalisedColumn() {
			ParsedFilter filter = parse("\"Gebäudehöhe\" > 12.5");

			assertThat(filter.sql()).isEqualTo("\"gebaeudehoehe\" > :f0");
			assertThat(filter.parameters()).containsEntry("f0", 12.5);
		}

		@Test
		void matchesFieldNamesRegardlessOfCase() {
			assertThat(parse("NAME = 'A'").sql()).isEqualTo("\"name\" = :f0");
		}

		@Test
		void alsoAcceptsTheNormalisedColumnName() {
			// The feature response keys its properties by column_name, so a filter
			// written straight from a response has to work -- same as the sort parameter.
			assertThat(parse("gebaeudehoehe > 1").sql()).isEqualTo("\"gebaeudehoehe\" > :f0");
		}

		@Test
		void treatsDoubledQuotesAsOneLiteralQuote() {
			assertThat(parse("name = 'O''Brien'").parameters()).containsEntry("f0", "O'Brien");
		}

		@Test
		void castsBoundStringsForDateColumns() {
			ParsedFilter filter = parse("erfasst_am > '2026-01-01'");

			assertThat(filter.sql()).isEqualTo("\"erfasst_am\" > CAST(:f0 AS timestamptz)");
		}

		@Test
		void returnsNullForABlankExpression() {
			assertThat(FilterParser.parse("   ", FIELDS)).isNull();
			assertThat(FilterParser.parse(null, FIELDS)).isNull();
		}
	}

	@Nested
	@DisplayName("type mismatches are reported, not passed to PostgreSQL")
	class Types {

		@Test
		void rejectsLikeOnANumericField() {
			assertThatThrownBy(() -> parse("einwohner LIKE '1%'"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("nur für Textfelder");
		}

		@Test
		void rejectsNonNumericTextForANumericField() {
			assertThatThrownBy(() -> parse("einwohner = 'viele'"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("ganze Zahl");
		}

		@Test
		void rejectsABooleanValueForATextField() {
			assertThatThrownBy(() -> parse("name = true"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("nicht boolesch");
		}

		@Test
		void acceptsANumberWrittenAsAStringForANumericField() {
			// Comes up constantly, because a filter bar has no types -- everything the
			// user types is text until it is parsed.
			assertThat(parse("einwohner > '1000'").parameters()).containsEntry("f0", 1000L);
		}

		@Test
		void acceptsGermanWordsForBooleans() {
			assertThat(parse("bewohnt = 'ja'").parameters()).containsEntry("f0", true);
			assertThat(parse("bewohnt = 'nein'").parameters()).containsEntry("f0", false);
		}
	}

	@Nested
	@DisplayName("syntax errors name their position")
	class Errors {

		@Test
		void reportsAnUnclosedQuote() {
			assertThatThrownBy(() -> parse("name = 'offen"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Nicht geschlossenes Hochkomma");
		}

		@Test
		void reportsAMissingOperator() {
			assertThatThrownBy(() -> parse("name 'A'"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Vergleichsoperator");
		}

		@Test
		void reportsAMissingClosingParenthesis() {
			assertThatThrownBy(() -> parse("(name = 'A'"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("schließende Klammer");
		}

		@Test
		void reportsTrailingInput() {
			assertThatThrownBy(() -> parse("name = 'A' 'B'"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Ende des Ausdrucks");
		}

		@Test
		void reportsAnUnexpectedCharacter() {
			assertThatThrownBy(() -> parse("name = @"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Unerwartetes Zeichen");
		}
	}
}
