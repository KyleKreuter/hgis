package de.kreuter.hgis.common;

/**
 * Request is syntactically fine but semantically invalid -- an unknown SRID, a
 * geometry that does not match the layer type, a filter referencing a missing field.
 * Mapped to 400 by {@link ProblemDetailAdvice}.
 */
public class BadRequestException extends RuntimeException {

	public BadRequestException(String message) {
		super(message);
	}
}
