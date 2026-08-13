package de.kreuter.hgis.wms;

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

/**
 * The answer when the WMS service the client named is the thing that failed, not this
 * backend. Same shape and reasoning as {@code geoportal.GeoportalOutageAdvice}: scoped to
 * the controllers that fetch a third-party service, ahead of {@code ProblemDetailAdvice}'s
 * catch-all, which would otherwise report it as this backend's own fault, and with the
 * same {@code Retry-After} on an answer worth trying again.
 */
@RestControllerAdvice(assignableTypes = { WmsCapabilitiesController.class, MapLayerController.class })
@Order(Ordered.HIGHEST_PRECEDENCE)
class WmsOutageAdvice {

	private static final Logger log = LoggerFactory.getLogger(WmsOutageAdvice.class);

	/** Long enough for a short outage to pass, short enough that a user still retries by hand. */
	private static final String RETRY_AFTER_SECONDS = "60";

	private static final String TITLE = "Dienst nicht erreichbar";

	@ExceptionHandler(WmsUnavailableException.class)
	ResponseEntity<ProblemDetail> handleUnavailable(WmsUnavailableException ex) {
		log.warn("WMS-Dienst antwortete nicht: {}", ex.getMessage());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
		problem.setTitle(TITLE);
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
				.body(problem);
	}
}
