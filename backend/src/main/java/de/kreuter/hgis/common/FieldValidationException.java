package de.kreuter.hgis.common;

/**
 * One request field failed a business rule that plain Bean Validation cannot express --
 * an enum token Jackson would otherwise reject only with a generic, unparseable body
 * error, or a check that spans several fields, such as a duplicate name.
 *
 * <p>Reported in the same {@code field -> message} shape the frontend already gets from
 * {@code @Valid} failures, so it never has to distinguish where a field error came from.
 * Mapped to 400 by {@link ProblemDetailAdvice}.
 */
public class FieldValidationException extends RuntimeException {

	private final String field;

	public FieldValidationException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}
}
