package de.kreuter.hgis.places;

/**
 * A refresh could not be completed -- the WFS did not answer, answered with something this
 * reader cannot parse, or the write itself failed. Never reaches the HTTP layer: {@code
 * POST /api/places/refresh} answers 202 before any of this runs (CONTRACT.md), so a failure
 * here is reported the same way {@code ImportFailedException} is, through the job's own
 * {@code message} field rather than a response status.
 */
class PlaceRefreshException extends RuntimeException {

	PlaceRefreshException(String message) {
		super(message);
	}

	PlaceRefreshException(String message, Throwable cause) {
		super(message, cause);
	}
}
