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

		/**
		 * The wording is part of the contract with the client, not just a nicety:
		 * {@code frontend/src/table/filterValidity.ts} matches on "Unbekanntes Feld" to
		 * tell a filter on a deleted field apart from any other 400, and discards that
		 * one filter instead of showing the attribute table as a failed request.
		 *
		 * <p>So if this assertion ever fails, adjusting it is not enough --
		 * {@code frontend/src/table/filterValidity.ts} has to move with it, or the table
		 * stops recovering from a filter that names a field that was deleted.
		 */
		@Test
		void anUnknownFieldIsRejectedAndTheKnownOnesAreNamed() {
			assertThatThrownBy(() -> parse("passwort = 'x'"))
					.isInstanceOf(BadRequestException.class)
					.as("frontend/src/table/filterValidity.ts matches on this wording -- change both or neither")
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

		/**
		 * A cast is not cosmetic for these three. PostgreSQL has no operator between
		 * {@code time}, {@code uuid} or {@code bytea} and the {@code varchar} a bound string
		 * arrives as, so without it the filter reached the database as an error rather than
		 * as a query -- and {@code time} is one of the nine types a field can be created
		 * with, while the other two come out of every import that carries them.
		 */
		@ParameterizedTest
		@ValueSource(strings = { "time", "uuid", "bytea" })
		void castsBoundStringsForColumnsWithoutAStringOperator(String type) {
			List<LayerField> fields = List.of(new LayerField(null, "wert", "wert", type, 0));

			ParsedFilter filter = FilterParser.parse("wert = 'x'", fields);

			assertThat(filter.sql()).isEqualTo("\"wert\" = CAST(:f0 AS " + type + ")");
		}

		@Test
		void returnsNullForABlankExpression() {
			assertThat(FilterParser.parse("   ", FIELDS)).isNull();
			assertThat(FilterParser.parse(null, FIELDS)).isNull();
		}
	}

	/**
	 * A name that means two fields is the bug this section exists for. The filter used to
	 * take the display-name match and the sort parameter the first field in ordinal order,
	 * so on the Straßenbaumkataster {@code kronendurchmesser > 10} counted 225.657 rows
	 * (a text column, compared as text) while sorting by the same word ordered a bigint
	 * column of 73.890 matches. Neither answer announced itself as the wrong one.
	 */
	@Nested
	@DisplayName("a name that means two fields is refused, not guessed")
	class Ambiguity {

		/** The shape from the Straßenbaumkataster: field 14's display name is field 13's column. */
		private static final List<LayerField> COLLIDING = List.of(
				new LayerField(null, "Kronendurchmesser Quelle", "kronendurchmesser", "bigint", 0),
				new LayerField(null, "Kronendurchmesser", "kronendurchmesser_z", "text", 1));

		@Test
		void refusesTheNameAndNamesBothCandidates() {
			assertThatThrownBy(() -> FilterParser.parse("kronendurchmesser > 10", COLLIDING))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Mehrdeutiges Feld: kronendurchmesser")
					.hasMessageContaining("Kronendurchmesser Quelle (Spalte kronendurchmesser)")
					.hasMessageContaining("Kronendurchmesser (Spalte kronendurchmesser_z)");
		}

		@Test
		void offersTheNamesThatDoResolve() {
			assertThatThrownBy(() -> FilterParser.parse("kronendurchmesser > 10", COLLIDING))
					.hasMessageContaining("Eindeutig sind: Kronendurchmesser Quelle, kronendurchmesser_z");
		}

		/**
		 * The message must not read as "this field is gone": the client discards a filter
		 * on the wording "Unbekanntes Feld" (frontend/src/table/filterValidity.ts), and
		 * discarding is wrong here -- the field exists twice and only the user can say which.
		 */
		@Test
		void doesNotLookLikeADeletedField() {
			assertThatThrownBy(() -> FilterParser.parse("kronendurchmesser > 10", COLLIDING))
					.hasMessageNotContaining("Unbekanntes Feld");
		}

		@Test
		void resolvesTheUnambiguousNamesOfTheSameTwoFields() {
			assertThat(FilterParser.parse("\"Kronendurchmesser Quelle\" > 10", COLLIDING).sql())
					.isEqualTo("\"kronendurchmesser\" > :f0");
			assertThat(FilterParser.parse("kronendurchmesser_z = 'x'", COLLIDING).sql())
					.isEqualTo("\"kronendurchmesser_z\" = :f0");
		}

		/** The ordinary case: display name and column name are the same word, one field. */
		@Test
		void aFieldNamedLikeItsOwnColumnIsNotAmbiguous() {
			assertThat(parse("einwohner > 1").sql()).isEqualTo("\"einwohner\" > :f0");
		}

		/**
		 * Two fields that swapped names -- reachable by renaming one onto the other's
		 * column -- leave neither with a name of its own. There is nothing to offer, and
		 * the message says that instead of naming an alternative that fails the same way.
		 */
		@Test
		void saysSoWhenNoNameResolves() {
			List<LayerField> fields = List.of(
					new LayerField(null, "wert_1", "wert", "bigint", 0),
					new LayerField(null, "wert", "wert_1", "text", 1));

			assertThatThrownBy(() -> FilterParser.parse("wert > 1", fields))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Kein Name spricht genau eines dieser Felder an");
			assertThatThrownBy(() -> FilterParser.parse("wert_1 > 1", fields))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Kein Name spricht genau eines dieser Felder an");
		}
	}

	@Nested
	@DisplayName("fid is a field like any other")
	class RowId {

		@Test
		void comparesLikeANumberColumn() {
			ParsedFilter filter = parse("fid > 100");

			assertThat(filter.sql()).isEqualTo("\"fid\" > :f0");
			assertThat(filter.parameters()).containsEntry("f0", 100L);
		}

		/**
		 * One bound array, not one parameter per value: a program re-reading a selection
		 * names every fid it holds, and PostgreSQL stops at 65535 bind parameters.
		 */
		@Test
		void binsAWholeInListIntoOneArrayParameter() {
			ParsedFilter filter = parse("fid IN (1, 2, 3)");

			assertThat(filter.sql()).isEqualTo("\"fid\" = ANY(:f0)");
			assertThat(filter.parameters()).hasSize(1);
			assertThat(filter.parameters().get("f0")).isEqualTo(new Long[] { 1L, 2L, 3L });
		}

		@Test
		void negatesAnInListWithAllRatherThanNotIn() {
			assertThat(parse("fid NOT IN (1, 2)").sql()).isEqualTo("\"fid\" <> ALL(:f0)");
		}

		@Test
		void staysOutOfTheWayOfOtherColumnsInLists() {
			assertThat(parse("einwohner IN (1, 2)").sql())
					.as("only fid changes shape -- every other column keeps its placeholders")
					.isEqualTo("\"einwohner\" IN (:f0, :f1)");
		}

		@Test
		void isRejectedForTextOperatorsLikeAnyOtherNumberColumn() {
			assertThatThrownBy(() -> parse("fid LIKE '1%'"))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("nur für Textfelder");
		}

		@Test
		void appearsAmongTheAvailableNames() {
			assertThatThrownBy(() -> parse("passwort = 'x'")).hasMessageContaining("fid");
		}

		/**
		 * An ESRI shapefile brings an attribute called FID. The row id must not quietly
		 * shadow it -- the column it was imported into is reachable, the name is not.
		 */
		@Test
		void refusesTheNameWhenTheLayerBroughtOneToo() {
			List<LayerField> fields = List.of(new LayerField(null, "FID", "fid_1", "bigint", 0));

			assertThatThrownBy(() -> FilterParser.parse("fid > 1", fields))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Mehrdeutiges Feld: fid")
					.hasMessageContaining("Eindeutig sind: fid_1");
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
