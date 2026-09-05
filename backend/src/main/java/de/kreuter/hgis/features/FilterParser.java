package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFields;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a small filter expression into a SQL fragment with bound parameters.
 *
 * <p>The client never sends SQL. It sends an expression over field names it can see,
 * and this parser is the only thing that decides what reaches the database. Two rules
 * carry that guarantee:
 *
 * <ul>
 *   <li>Every identifier is resolved through {@code layer_field}. A name that is not a
 *       field of this layer is rejected -- the resolved {@code column_name} is quoted by
 *       {@link SqlIdentifier}, never the text the client typed.
 *   <li>Every literal becomes a bind parameter. No value is ever concatenated into the
 *       fragment, so quoting a value cannot end the string and start a statement.
 * </ul>
 *
 * <p>Names are resolved by {@link LayerFields#require}, the same rule the sort parameter
 * uses -- the two used to resolve a name that means two fields to opposite ones, so
 * filtering and sorting by the same word silently read different columns.
 * {@link QueryFields} adds {@code fid} to what may be named.
 *
 * <p>Supported: comparisons ({@code = <> != < <= > >=}), {@code LIKE}/{@code ILIKE},
 * {@code IS [NOT] NULL}, {@code IN (…)}, {@code AND}, {@code OR}, {@code NOT} and
 * parentheses. Field names containing spaces go in double quotes, values in single
 * quotes: {@code "Fläche in m2" > 10 AND nutzung LIKE 'Wohn%'}. Umlauts need no quotes --
 * {@code readWord} reads any letter {@link Character#isLetter} accepts, so
 * {@code Gebäudehöhe > 10} resolves like any other name. Quoting one anyway still works.
 *
 * <p>A field id may be written where a name may, and without quotes:
 * {@code 019ff731-1f0c-7de5-9100-b9022e19ea3f > 10}. That is what the message about an
 * ambiguous name offers as the way that always resolves, so it has to work as printed.
 */
public final class FilterParser {

	/** SQL fragment plus the values it binds. The fragment carries no literal from the client. */
	public record ParsedFilter(String sql, Map<String, Object> parameters) {
	}

	/** Everything an identifier may name: the layer's fields plus {@code fid}. */
	private final List<LayerField> fields;
	private final Map<String, Object> parameters = new LinkedHashMap<>();
	private final List<Token> tokens;
	private int position;

	private FilterParser(String expression, List<LayerField> fields) {
		this.fields = QueryFields.withRowId(fields);
		this.tokens = tokenize(expression);
	}

	/**
	 * @param expression user-supplied filter; blank means "no filter"
	 * @param fields the layer's fields; those and {@code fid} are the only identifiers
	 *     that may appear
	 * @return the fragment, or null when nothing was filtered
	 * @throws BadRequestException on any syntax error, unknown field or ambiguous name
	 */
	public static ParsedFilter parse(String expression, List<LayerField> fields) {
		if (expression == null || expression.isBlank()) {
			return null;
		}
		FilterParser parser = new FilterParser(expression, fields);
		String sql = parser.parseOr();
		parser.expect(TokenType.END, "Ende des Ausdrucks");
		return new ParsedFilter(sql, parser.parameters);
	}

	// --- grammar ----------------------------------------------------------------------

	private String parseOr() {
		String left = parseAnd();
		while (matchKeyword("OR")) {
			left = "(" + left + " OR " + parseAnd() + ")";
		}
		return left;
	}

	private String parseAnd() {
		String left = parsePrimary();
		while (matchKeyword("AND")) {
			left = "(" + left + " AND " + parsePrimary() + ")";
		}
		return left;
	}

	private String parsePrimary() {
		if (matchKeyword("NOT")) {
			return "NOT (" + parsePrimary() + ")";
		}
		if (match(TokenType.LPAREN)) {
			String inner = parseOr();
			expect(TokenType.RPAREN, "schließende Klammer");
			// Returned unwrapped: parseOr and parseAnd already parenthesise whenever they
			// combine operands, so adding another pair here would only nest redundantly.
			return inner;
		}
		return parseComparison();
	}

	private String parseComparison() {
		LayerField field = resolveField();
		String column = SqlIdentifier.quoteColumn(field.getColumnName());

		if (matchKeyword("IS")) {
			boolean negated = matchKeyword("NOT");
			expectKeyword("NULL");
			return column + (negated ? " IS NOT NULL" : " IS NULL");
		}

		boolean negated = matchKeyword("NOT");
		if (matchKeyword("IN")) {
			return parseIn(field, column, negated);
		}
		if (matchKeyword("LIKE") || matchKeyword("ILIKE")) {
			String operator = previous().text().toUpperCase(Locale.ROOT);
			requireTextual(field, operator);
			return column + (negated ? " NOT " : " ") + operator + " " + bind(field, readLiteral(field));
		}
		if (negated) {
			throw error("Nach NOT erwartet das Programm IN, LIKE oder ILIKE");
		}

		Token operator = expect(TokenType.OPERATOR, "Vergleichsoperator");
		requireOrderableAgainstNumber(field, operator);
		return column + " " + operator.text() + " " + bind(field, readLiteral(field));
	}

	/** The four operators that ask for an order rather than for equality. */
	private static final List<String> ORDERING_OPERATORS = List.of("<", "<=", ">", ">=");

	/**
	 * Refuses to order a text column against a number.
	 *
	 * <p>PostgreSQL compares text character by character, so {@code '9'} sorts after
	 * {@code '10'}. On the Straßenbaumkataster's {@code kronendurchmesser_z} -- a column of
	 * values like "8 m" -- {@code > 10} therefore matched 225.657 of 229.876 rows where the
	 * honest answer is 73.890. No error, no hint, and a result that looks like a result.
	 *
	 * <p>Only this one shape is refused, because only here is the intent legible. A bare
	 * number after an ordering operator says "compare quantities"; the same value in single
	 * quotes says "compare text" and is still served. {@code =}, {@code <>}, {@code IN} and
	 * {@code LIKE} are untouched: equality on text is exact whichever way the value was
	 * written, and none of them implies an order.
	 *
	 * <p>Casting the column instead was measured and rejected: of the 229.494 filled values
	 * in that column, 229.067 are not a number, so the cast would answer the whole layer with
	 * an error rather than with rows.
	 *
	 * <p>Sorting by such a column stays lexical on purpose. There is no literal there to read
	 * an intent from, and of the 29 text columns in the real data only two hold quantities --
	 * for the other 27 a lexical order is the right one.
	 */
	private void requireOrderableAgainstNumber(LayerField field, Token operator) {
		if (!ORDERING_OPERATORS.contains(operator.text())
				|| !baseType(field).equals("text")
				|| current().type() != TokenType.NUMBER) {
			return;
		}
		throw new BadRequestException("Feld " + field.getSourceName() + " ist vom Typ "
				+ field.getDataType() + ". Der Operator " + operator.text()
				+ " vergleicht dann Zeichen für Zeichen: '9' gilt als größer als '10'."
				+ " Zahlenfelder dieses Layers: " + String.join(", ", numericFieldNames()) + "."
				+ " Für einen Textvergleich setzen Sie den Wert in Hochkommas.");
	}

	/**
	 * The layer's numeric fields -- what the client should have named.
	 *
	 * <p>Listed as a fact about the layer, not guessed from the name that failed: on the
	 * Straßenbaumkataster this puts "Kronendurchmesser Quelle" in front of someone who wrote
	 * "Kronendurchmesser", without this parser knowing anything about a {@code _z} suffix.
	 * The order is the layer's own field order, for the same reason -- a predictable list
	 * beats a clever one.
	 *
	 * <p>The id is spent only where the name does not carry. The question here is "which
	 * field holds numbers", and a name answers it; a field whose name means two fields would
	 * send the reader into a second error to find that out, so that one is named by id as
	 * well. A field that has no id yet -- unsaved, or the synthetic {@code fid} -- is left
	 * with its name, because "Id null" is worse than nothing.
	 *
	 * <p>Never empty, because {@code fid} is in the list and is a {@code bigint}. That is
	 * what spares this message a second wording for a layer with no numeric field of its own.
	 */
	private List<String> numericFieldNames() {
		return fields.stream()
				.filter(LayerFields::isNumeric)
				.map(this::describeNumeric)
				.toList();
	}

	private String describeNumeric(LayerField candidate) {
		String name = candidate.getSourceName();
		if (candidate.getId() == null || LayerFields.resolvesUniquely(name, fields)) {
			return name;
		}
		return name + " (Id " + candidate.getId() + ")";
	}

	/**
	 * {@code IN} over a list of values.
	 *
	 * <p>For {@code fid} the whole list becomes one bound array compared with
	 * {@code = ANY(…)}, not one placeholder per value. That is the case where the list gets
	 * long: a program re-reads a selection by naming the fids it holds, and a selection runs
	 * to five figures here (see {@code FidSelection.MAX_FIDS}). Expanded, that would be one
	 * bind parameter per object against PostgreSQL's ceiling of 65535 -- the export already
	 * passes its selection as one array for exactly this reason. Any other column keeps the
	 * expanded form: its values carry a type that would have to be spelled into the array
	 * cast, and no client sends thousands of them.
	 */
	private String parseIn(LayerField field, String column, boolean negated) {
		expect(TokenType.LPAREN, "öffnende Klammer nach IN");
		List<Object> values = new ArrayList<>();
		do {
			values.add(readLiteral(field));
		}
		while (match(TokenType.COMMA));
		expect(TokenType.RPAREN, "schließende Klammer");

		if (QueryFields.isRowId(field)) {
			// <> ALL is NOT IN written for an array. fid is NOT NULL, so the two agree on
			// every row -- the difference NULL makes between them cannot arise here.
			String placeholder = bindArray(values);
			return column + (negated ? " <> ALL(" : " = ANY(") + placeholder + ")";
		}

		List<String> placeholders = values.stream().map(value -> bind(field, value)).toList();
		return column + (negated ? " NOT IN " : " IN ")
				+ "(" + String.join(", ", placeholders) + ")";
	}

	private LayerField resolveField() {
		Token token = current();
		if (token.type() != TokenType.IDENTIFIER) {
			throw error("Feldname erwartet. Gefunden: " + describe(token) + ".");
		}
		advance();
		return LayerFields.require(token.text(), fields, "Feld");
	}

	// --- values -----------------------------------------------------------------------

	/**
	 * Reads one literal and converts it to the field's type.
	 *
	 * Converting here rather than letting PostgreSQL cast means a mismatch is reported as
	 * "Feld x erwartet eine Zahl" instead of surfacing as a database error the user
	 * cannot act on.
	 */
	private Object readLiteral(LayerField field) {
		Token token = current();
		advance();

		return switch (token.type()) {
			case STRING -> convertString(field, token.text());
			case NUMBER -> convertNumber(field, token.text());
			case KEYWORD -> convertKeyword(field, token.text());
			default -> throw error("Wert erwartet. Gefunden: " + describe(token) + ".");
		};
	}

	private Object convertString(LayerField field, String text) {
		return switch (baseType(field)) {
			case "integer", "bigint" -> parseLong(field, text);
			case "double precision", "numeric", "real" -> parseDouble(field, text);
			case "boolean" -> parseBoolean(field, text);
			// Dates stay strings and are cast in SQL: PostgreSQL parses ISO input
			// correctly and applies the session time zone, which duplicating in Java
			// would only get subtly wrong.
			default -> text;
		};
	}

	private Object convertNumber(LayerField field, String text) {
		return switch (baseType(field)) {
			case "integer", "bigint" -> parseLong(field, text);
			case "double precision", "numeric", "real" -> parseDouble(field, text);
			// A number against a text column stays the text it was written as, which is
			// exact for =, <> and IN. The ordering operators are the ones that would read
			// it wrongly, and they never get here -- see requireOrderableAgainstNumber.
			case "text" -> text;
			default -> throw new BadRequestException(
					"Feld " + field.getSourceName() + " ist vom Typ " + field.getDataType()
							+ ". Vergleich mit einer Zahl ist nicht möglich.");
		};
	}

	private Object convertKeyword(LayerField field, String text) {
		String upper = text.toUpperCase(Locale.ROOT);
		if (!upper.equals("TRUE") && !upper.equals("FALSE")) {
			throw error("Wert erwartet. Gefunden: " + text + ".");
		}
		if (!baseType(field).equals("boolean")) {
			throw new BadRequestException("Feld " + field.getSourceName()
					+ " ist vom Typ " + field.getDataType() + ". Es ist nicht boolesch.");
		}
		return upper.equals("TRUE");
	}

	private void requireTextual(LayerField field, String operator) {
		if (!baseType(field).equals("text")) {
			throw new BadRequestException(operator + " ist nur für Textfelder möglich. "
					+ field.getSourceName() + " ist vom Typ " + field.getDataType() + ".");
		}
	}

	/** Strips a length or precision suffix: "numeric(10,2)" -> "numeric". */
	private static String baseType(LayerField field) {
		String type = field.getDataType().toLowerCase(Locale.ROOT);
		int parenthesis = type.indexOf('(');
		return parenthesis < 0 ? type : type.substring(0, parenthesis);
	}

	private static long parseLong(LayerField field, String text) {
		try {
			return Long.parseLong(text.trim());
		}
		catch (NumberFormatException ex) {
			throw new BadRequestException(
					"Feld " + field.getSourceName() + " erwartet eine ganze Zahl. Gefunden: " + text + ".");
		}
	}

	private static double parseDouble(LayerField field, String text) {
		try {
			return Double.parseDouble(text.trim());
		}
		catch (NumberFormatException ex) {
			throw new BadRequestException(
					"Feld " + field.getSourceName() + " erwartet eine Zahl. Gefunden: " + text + ".");
		}
	}

	private static boolean parseBoolean(LayerField field, String text) {
		String value = text.trim().toLowerCase(Locale.ROOT);
		return switch (value) {
			case "true", "wahr", "ja", "1" -> true;
			case "false", "falsch", "nein", "0" -> false;
			default -> throw new BadRequestException(
					"Feld " + field.getSourceName() + " erwartet wahr oder falsch. Gefunden: " + text + ".");
		};
	}

	/**
	 * Registers a value and returns its placeholder. A column whose type a bound string
	 * cannot be compared against directly gets an explicit cast -- see {@link #castFor}.
	 */
	private String bind(LayerField field, Object value) {
		String name = "f" + parameters.size();
		parameters.put(name, value);

		String cast = value instanceof String ? castFor(baseType(field)) : null;
		return cast == null ? ":" + name : "CAST(:" + name + " AS " + cast + ")";
	}

	/**
	 * Registers a whole value list as one {@code bigint[]} and returns its placeholder.
	 *
	 * <p>Only reached for {@code fid}, whose literals are already {@link Long} by the time
	 * they get here: {@link #readLiteral} converts against the field's type, and the row id
	 * is declared {@code bigint}.
	 */
	private String bindArray(List<Object> values) {
		String name = "f" + parameters.size();
		parameters.put(name, values.stream().map(Long.class::cast).toArray(Long[]::new));
		return ":" + name;
	}

	/**
	 * The SQL type a bound string has to be read as for this column, or null when it can be
	 * compared as it is.
	 *
	 * <p>Two different reasons meet here. A date or timestamp would otherwise be compared
	 * lexically, which quietly gives the wrong rows. A {@code time}, {@code uuid} or
	 * {@code bytea} has no operator against the {@code varchar} a bound string arrives as at
	 * all, so the whole filter came back as a 500 -- and {@code time} is one of the nine
	 * types a field can be created with, while the other two come out of an import.
	 */
	private static String castFor(String type) {
		if (type.startsWith("timestamp")) {
			return "timestamptz";
		}
		return switch (type) {
			case "date", "time", "uuid", "bytea" -> type;
			default -> null;
		};
	}

	// --- tokenizer --------------------------------------------------------------------

	private enum TokenType { IDENTIFIER, STRING, NUMBER, KEYWORD, OPERATOR, LPAREN, RPAREN, COMMA, END }

	private record Token(TokenType type, String text, int position) {
	}

	private static final List<String> KEYWORDS =
			List.of("AND", "OR", "NOT", "IS", "NULL", "LIKE", "ILIKE", "IN", "TRUE", "FALSE");

	private static List<Token> tokenize(String input) {
		List<Token> result = new ArrayList<>();
		int index = 0;

		while (index < input.length()) {
			char c = input.charAt(index);

			if (Character.isWhitespace(c)) {
				index++;
			}
			else if (c == '(') {
				result.add(new Token(TokenType.LPAREN, "(", index++));
			}
			else if (c == ')') {
				result.add(new Token(TokenType.RPAREN, ")", index++));
			}
			else if (c == ',') {
				result.add(new Token(TokenType.COMMA, ",", index++));
			}
			else if (c == '\'') {
				index = readString(input, index, result);
			}
			else if (c == '"') {
				index = readQuotedIdentifier(input, index, result);
			}
			else if (isOperatorStart(c)) {
				index = readOperator(input, index, result);
			}
			// Before the number and the word: a field id starts with a hex digit or a
			// letter, and either branch would tear it apart at the first dash.
			else if (isFieldIdAt(input, index)) {
				result.add(new Token(TokenType.IDENTIFIER,
						input.substring(index, index + FIELD_ID_LENGTH), index));
				index += FIELD_ID_LENGTH;
			}
			else if (Character.isDigit(c) || (c == '-' && index + 1 < input.length()
					&& Character.isDigit(input.charAt(index + 1)))) {
				index = readNumber(input, index, result);
			}
			else if (Character.isLetter(c) || c == '_') {
				index = readWord(input, index, result);
			}
			else {
				throw new BadRequestException(
						"Unerwartetes Zeichen '" + c + "' an Position " + (index + 1));
			}
		}

		result.add(new Token(TokenType.END, "", input.length()));
		return result;
	}

	private static int readString(String input, int start, List<Token> result) {
		StringBuilder value = new StringBuilder();
		int index = start + 1;

		while (index < input.length()) {
			char c = input.charAt(index);
			if (c == '\'') {
				// SQL's own escape: '' inside a string is one literal quote. Recognising
				// it here means O'Brien can be filtered for, written as 'O''Brien'.
				if (index + 1 < input.length() && input.charAt(index + 1) == '\'') {
					value.append('\'');
					index += 2;
					continue;
				}
				result.add(new Token(TokenType.STRING, value.toString(), start));
				return index + 1;
			}
			value.append(c);
			index++;
		}
		throw new BadRequestException("Nicht geschlossenes Hochkomma ab Position " + (start + 1));
	}

	/** Characters in {@code 8-4-4-4-12}, the shape {@link java.util.UUID#toString} writes. */
	private static final int FIELD_ID_LENGTH = 36;

	/** That same shape as a pattern: hex digits everywhere the dashes are not. */
	private static final Pattern FIELD_ID = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	/**
	 * Whether a field id, written plainly, starts here.
	 *
	 * <p>Recognised without quotes because the ambiguity message hands the id out as the
	 * way that always resolves, and a way out that has to be quoted before it works is a
	 * second thing to know. Quoting still works -- this only removes the need for it.
	 *
	 * <p>Nothing else can be read as this shape: a column name never contains a dash
	 * ({@code SqlIdentifier.SAFE_COLUMN}), and a number stops at the first one. A display
	 * name could in principle be spelled like an id, and then the name is ambiguous -- which
	 * {@link de.kreuter.hgis.catalog.LayerFields} reports, exactly as for any other collision.
	 */
	private static boolean isFieldIdAt(String input, int start) {
		return start + FIELD_ID_LENGTH <= input.length()
				&& FIELD_ID.matcher(input).region(start, start + FIELD_ID_LENGTH).matches();
	}

	private static int readQuotedIdentifier(String input, int start, List<Token> result) {
		int end = input.indexOf('"', start + 1);
		if (end < 0) {
			throw new BadRequestException(
					"Nicht geschlossenes Anführungszeichen ab Position " + (start + 1));
		}
		result.add(new Token(TokenType.IDENTIFIER, input.substring(start + 1, end), start));
		return end + 1;
	}

	private static boolean isOperatorStart(char c) {
		return c == '=' || c == '<' || c == '>' || c == '!';
	}

	private static int readOperator(String input, int start, List<Token> result) {
		String two = input.length() > start + 1 ? input.substring(start, start + 2) : "";
		if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=")) {
			// != is accepted as input but emitted as SQL's <>, so only one spelling of
			// inequality ever reaches the database.
			result.add(new Token(TokenType.OPERATOR, two.equals("!=") ? "<>" : two, start));
			return start + 2;
		}
		char c = input.charAt(start);
		if (c == '=' || c == '<' || c == '>') {
			result.add(new Token(TokenType.OPERATOR, String.valueOf(c), start));
			return start + 1;
		}
		throw new BadRequestException("Unvollständiger Operator an Position " + (start + 1));
	}

	private static int readNumber(String input, int start, List<Token> result) {
		int index = start + 1;
		while (index < input.length()
				&& (Character.isDigit(input.charAt(index)) || input.charAt(index) == '.')) {
			index++;
		}
		result.add(new Token(TokenType.NUMBER, input.substring(start, index), start));
		return index;
	}

	private static int readWord(String input, int start, List<Token> result) {
		int index = start;
		while (index < input.length()
				&& (Character.isLetterOrDigit(input.charAt(index)) || input.charAt(index) == '_')) {
			index++;
		}
		String word = input.substring(start, index);
		TokenType type = KEYWORDS.contains(word.toUpperCase(Locale.ROOT))
				? TokenType.KEYWORD
				: TokenType.IDENTIFIER;
		result.add(new Token(type, word, start));
		return index;
	}

	// --- token stream -----------------------------------------------------------------

	private Token current() {
		return tokens.get(position);
	}

	private Token previous() {
		return tokens.get(position - 1);
	}

	private void advance() {
		if (position < tokens.size() - 1) {
			position++;
		}
	}

	private boolean match(TokenType type) {
		if (current().type() == type) {
			advance();
			return true;
		}
		return false;
	}

	private boolean matchKeyword(String keyword) {
		Token token = current();
		if (token.type() == TokenType.KEYWORD && token.text().equalsIgnoreCase(keyword)) {
			advance();
			return true;
		}
		return false;
	}

	private void expectKeyword(String keyword) {
		if (!matchKeyword(keyword)) {
			throw error(keyword + " erwartet. Gefunden: " + describe(current()) + ".");
		}
	}

	private Token expect(TokenType type, String expected) {
		Token token = current();
		if (token.type() != type) {
			throw error(expected + " erwartet. Gefunden: " + describe(token) + ".");
		}
		advance();
		return token;
	}

	private static String describe(Token token) {
		return token.type() == TokenType.END ? "Ende des Ausdrucks" : "'" + token.text() + "'";
	}

	private BadRequestException error(String message) {
		return new BadRequestException(
				"Filter ungültig an Position " + (current().position() + 1) + ": " + message);
	}
}
