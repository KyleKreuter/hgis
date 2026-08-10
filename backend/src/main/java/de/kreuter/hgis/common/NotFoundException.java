package de.kreuter.hgis.common;

/** Requested resource does not exist. Mapped to 404 by {@link ProblemDetailAdvice}. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}
}
