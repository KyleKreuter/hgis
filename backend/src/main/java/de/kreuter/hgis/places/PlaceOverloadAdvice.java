package de.kreuter.hgis.places;

import java.util.concurrent.RejectedExecutionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The answer when {@link de.kreuter.hgis.jobs.AsyncConfig#IMPORT_EXECUTOR} -- shared with
 * imports, see {@link PlaceRefreshService}'s class doc -- has nothing left to give a place
 * refresh. Modelled on {@code ingest.ImportOverloadAdvice}, scoped the same way: to
 * {@link PlaceController} alone, since rejection is turned into its own job-failure message
 * first (see {@link PlaceController#refresh}) and this only decides what the client is told.
 */
@RestControllerAdvice(assignableTypes = PlaceController.class)
// Ahead of ProblemDetailAdvice, whose handler for Exception would otherwise claim this one
// first and label it a server fault.
@Order(Ordered.HIGHEST_PRECEDENCE)
class PlaceOverloadAdvice {

	/** Long enough for a running import to make room, short enough to still be a retry. */
	private static final String RETRY_AFTER_SECONDS = "30";

	@ExceptionHandler(RejectedExecutionException.class)
	ResponseEntity<ProblemDetail> handleRejected(RejectedExecutionException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"Es läuft bereits zu viel im Hintergrund. Starten Sie den Abzug in einem Moment erneut.");
		problem.setTitle("Server ausgelastet");

		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
				.body(problem);
	}
}
