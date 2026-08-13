package de.kreuter.hgis.geoportal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/**
 * The answer when Hamburg's Geoportal is the thing that failed, not this backend.
 *
 * <p>Every call these two controllers make can end at an upstream service nothing here
 * controls: a 5xx from the collection endpoint, a read that runs past the 60-second timeout
 * {@link GeoportalHttpClientConfig} sets, or a first catalog load that cannot fetch the two
 * upstream files at all. None of those had a handler, so all three fell through to {@code
 * ProblemDetailAdvice}'s catch-all and reached the user as 500 "Interner Fehler" -- after a
 * minute of waiting, the one message that is certainly wrong, because it says the program
 * is broken when in truth a service it depends on did not answer. The distinction matters
 * to the user: an outage is worth retrying, a broken program is not.
 *
 * <p>Same shape and same reasoning as {@code ExportOverloadAdvice}: scoped to the
 * controllers it applies to, ahead of the catch-all that would otherwise claim these first,
 * and with a {@code Retry-After} on everything that is worth trying again.
 */
@RestControllerAdvice(assignableTypes = { GeoportalCatalogController.class, GeoportalImportController.class })
// Ahead of ProblemDetailAdvice, whose handler for Exception would otherwise claim these
// and label them a server fault.
@Order(Ordered.HIGHEST_PRECEDENCE)
class GeoportalOutageAdvice {

	private static final Logger log = LoggerFactory.getLogger(GeoportalOutageAdvice.class);

	/** Long enough for a short outage to pass, short enough that a user still retries by hand. */
	private static final String RETRY_AFTER_SECONDS = "60";

	private static final String TITLE = "Geoportal nicht erreichbar";

	/** The service answered with a fault of its own -- a bad answer from upstream, i.e. 502. */
	@ExceptionHandler(GeoportalUnavailableException.class)
	ResponseEntity<ProblemDetail> handleUpstreamFault(GeoportalUnavailableException ex) {
		log.warn("Geoportal antwortete mit einem Serverfehler: {}", ex.getMessage());
		return problem(HttpStatus.BAD_GATEWAY,
				"Das Geoportal Hamburg meldet einen Fehler. Versuchen Sie es später erneut.");
	}

	/** No answer at all: connection refused, host unreachable, or past the read timeout. */
	@ExceptionHandler(ResourceAccessException.class)
	ResponseEntity<ProblemDetail> handleNoAnswer(ResourceAccessException ex) {
		log.warn("Geoportal antwortete nicht: {}", ex.getMessage());
		return problem(HttpStatus.SERVICE_UNAVAILABLE,
				"Das Geoportal Hamburg antwortet nicht. Versuchen Sie es später erneut.");
	}

	/**
	 * The first catalog load of a session failed and there is no held copy to fall back on
	 * ({@link GeoportalCatalogService#refresh}) -- the dataset list stays empty until the
	 * upstream files are reachable again.
	 */
	@ExceptionHandler(CatalogLoadException.class)
	ResponseEntity<ProblemDetail> handleCatalogLoad(CatalogLoadException ex) {
		log.warn("Katalog des Geoportals kann nicht geladen werden: {}", ex.getMessage());
		return problem(HttpStatus.SERVICE_UNAVAILABLE,
				"Das Programm kann den Katalog vom Geoportal Hamburg nicht laden. Versuchen Sie es später erneut.");
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(TITLE);
		return ResponseEntity.status(status)
				.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
				.body(problem);
	}
}
