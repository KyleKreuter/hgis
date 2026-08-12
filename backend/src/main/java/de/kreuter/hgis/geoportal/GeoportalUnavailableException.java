package de.kreuter.hgis.geoportal;

/**
 * Hamburg's Geoportal answered, but with a server fault of its own (5xx). Its own type
 * rather than a plain {@link IllegalStateException} so {@link GeoportalOutageAdvice} can
 * tell it apart from a genuine bug in this backend -- the two used to be the same class and
 * therefore the same 500 "Interner Fehler" to the user, which named the wrong culprit.
 */
class GeoportalUnavailableException extends RuntimeException {

	GeoportalUnavailableException(String message) {
		super(message);
	}
}
