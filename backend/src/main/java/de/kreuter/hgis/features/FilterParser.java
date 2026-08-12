package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * <p>Supported: comparisons ({@code = <> != < <= > >=}), {@code LIKE}/{@code ILIKE},
 * {@code IS [NOT] NULL}, {@code IN (…)}, {@code AND}, {@code OR}, {@code NOT} and
 * parentheses. Field names containing spaces or umlauts go in double quotes, values in
 * single quotes: {@code "Gebäudehöhe" > 10 AND nutzung LIKE 'Wohn%'}.
 */
public final class FilterParser {

	/** SQL fragment plus the values it binds. The fragment carries no literal from the client. */
	public record ParsedFilter(String sql, Map<String, Object> parameters) {
	}

	private final Map<String, LayerField> fieldsBySourceName = new LinkedHashMap<>();
	private final Map<String, LayerField> fieldsByColumnName = new LinkedHashMap<>();
	private final Map<String, Object> parameters = new LinkedHashMap<>();
	private final List<Token> tokens;
	private int position;

	private FilterParser(String expression, List<LayerField> fields) {
		for (LayerField field : fields) {
			// Matching is case-insensitive: the UI shows the source name as it came from
			// the file, and expecting someone to reproduce its capitalisation is a trap.
			fieldsBySourceName.put(field.getSourceName().toLowerCase(Locale.ROOT), field);
			// The normalised column name is accepted as well, because that is the key the
			// feature response uses for its properties -- someone reading a response and
			// writing a filter from it should not have to translate. Source names win on
			// a clash, matching the sort parameter.
			fieldsByColumnName.putIfAbsent(field.getColumnName().toLowerCase(Locale.ROOT), field);
		}
		this.tokens = tokenize(expression);
	}

	/**
	 * @param expression user-supplied filter; blank means "no filter"
	 * @param fields the layer's fields, the only identifiers that may appear
	 * @return the fragment, or null when nothing was filtered
	 * @throws BadRequestException on any syntax error or unknown field
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
			return column + (negated ? " NOT IN " : " IN ") + parseValueList(field);
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
		return column + " " + operator.text() + " " + bind(field, readLiteral(field));
	}

	private String parseValueList(LayerField field) {
		expect(TokenType.LPAREN, "öffnende Klammer nach IN");
		List<String> placeholders = new ArrayList<>();
		do {
			placeholders.add(bind(field, readLiteral(field)));
		}
		while (match(TokenType.COMMA));
		expect(TokenType.RPAREN, "schließende Klammer");
		return "(" + String.join(", ", placeholders) + ")";
	}

	private LayerField resolveField() {
		Token token = current();
		if (token.type() != TokenType.IDENTIFIER) {
			throw error("Feldname erwartet. Gefunden: " + describe(token));
		}
		advance();
		String name = token.text().toLowerCase(Locale.ROOT);
		LayerField field = fieldsBySourceName.getOrDefault(name, fieldsByColumnName.get(name));
		if (field == null) {
			throw new BadRequestException("Unbekanntes Feld: " + token.text()
					+ ". Verfügbar: " + String.join(", ", availableFieldNames()));
		}
		return field;
	}

	private List<String> availableFieldNames() {
		return fieldsBySourceName.values().stream().map(LayerField::getSourceName).toList();
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
			default -> throw error("Wert erwartet. Gefunden: " + describe(token));
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
			case "text" -> text;
			default -> throw new BadRequestException(
					"Feld " + field.getSourceName() + " ist vom Typ " + field.getDataType()
							+ ". Vergleich mit einer Zahl ist nicht möglich.");
		};
	}

	private Object convertKeyword(LayerField field, String text) {
		String upper = text.toUpperCase(Locale.ROOT);
		if (!upper.equals("TRUE") && !upper.equals("FALSE")) {
			throw error("Wert erwartet. Gefunden: " + text);
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
					"Feld " + field.getSourceName() + " erwartet eine ganze Zahl. Gefunden: " + text);
		}
	}

	private static double parseDouble(LayerField field, String text) {
		try {
			return Double.parseDouble(text.trim());
		}
		catch (NumberFormatException ex) {
			throw new BadRequestException(
					"Feld " + field.getSourceName() + " erwartet eine Zahl. Gefunden: " + text);
		}
	}

	private static boolean parseBoolean(LayerField field, String text) {
		String value = text.trim().toLowerCase(Locale.ROOT);
		return switch (value) {
			case "true", "wahr", "ja", "1" -> true;
			case "false", "falsch", "nein", "0" -> false;
			default -> throw new BadRequestException(
					"Feld " + field.getSourceName() + " erwartet wahr oder falsch. Gefunden: " + text);
		};
	}

	/**
	 * Registers a value and returns its placeholder. Date and timestamp columns get an
	 * explicit cast so a bound string is compared as a date rather than lexically.
	 */
	private String bind(LayerField field, Object value) {
		String name = "f" + parameters.size();
		parameters.put(name, value);

		String type = baseType(field);
		if (value instanceof String && (type.equals("date") || type.startsWith("timestamp"))) {
			return "CAST(:" + name + " AS " + (type.equals("date") ? "date" : "timestamptz") + ")";
		}
		return ":" + name;
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
			throw error(keyword + " erwartet. Gefunden: " + describe(current()));
		}
	}

	private Token expect(TokenType type, String expected) {
		Token token = current();
		if (token.type() != type) {
			throw error(expected + " erwartet. Gefunden: " + describe(token));
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
