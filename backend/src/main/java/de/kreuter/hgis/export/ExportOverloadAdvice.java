package de.kreuter.hgis.export;

import java.util.concurrent.RejectedExecutionException;
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
 * The answer when the export pool has nothing left to give.
 *
 * <p>{@link ExportAsyncConfig} caps how many downloads may run and how many may wait. Past
 * that the executor rejects the task, and Spring MVC reports it as a
 * {@code TaskRejectedException} while the response is still nothing but a status line --
 * headers are written, but the first byte of the body is what commits it, and that byte is
 * exactly what was never produced. So the status can still be corrected, and it should be:
 * without this the rejection falls through to the catch-all and reads as 500, telling a
 * client that something broke when in truth it only came too early.
 *
 * <p>Scoped to {@link ExportController}. Rejection means something different for every
 * pool, and no other endpoint should inherit this reading of it.
 *
 * <p>What cannot be recovered is a rejection that arrives after the body has started,
 * because there is no such thing: the task is submitted before it writes anything. The
 * remaining gap is Spring's own -- {@code WebAsyncManager} both dispatches the error and
 * rethrows the rejection, so a container may log it a second time even though the client
 * receives one clean 503.
 */
@RestControllerAdvice(assignableTypes = ExportController.class)
// Ahead of ProblemDetailAdvice, whose handler for Exception would otherwise claim this
// one first and label it a server fault.
@Order(Ordered.HIGHEST_PRECEDENCE)
class ExportOverloadAdvice {

	private static final Logger log = LoggerFactory.getLogger(ExportOverloadAdvice.class);

	/** Long enough for a running export to make room, short enough to still be a retry. */
	private static final String RETRY_AFTER_SECONDS = "30";

	@ExceptionHandler(RejectedExecutionException.class)
	ResponseEntity<ProblemDetail> handleRejected(RejectedExecutionException ex) {
		// Not an error: the limit did what it is for. Worth a line all the same, because
		// a queue that fills regularly is a sizing question rather than a client one.
		log.warn("Export abgelehnt, Pool ausgelastet: {}", ex.getMessage());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"Es laufen bereits zu viele Exporte. Bitte in einem Moment erneut versuchen.");
		problem.setTitle("Server ausgelastet");

		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
				.body(problem);
	}
}
