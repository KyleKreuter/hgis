package de.kreuter.hgis.ingest;

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
 * The answer when the import pool has nothing left to give.
 *
 * <p>{@link de.kreuter.hgis.jobs.AsyncConfig} caps how many imports may run and how many
 * may wait. Past that the executor rejects the task while the request thread is still
 * inside {@link ImportController#startImport}, so the rejection is a plain synchronous
 * exception -- nothing of the response has been written, and the status is still ours to
 * choose. Without this it falls through to the catch-all and reads as 500, telling the user
 * that something broke when the truth is that they came too early.
 *
 * <p>Modelled on {@code ExportOverloadAdvice}, and scoped the same way: to
 * {@link ImportController} alone. Rejection means something different for every pool, and
 * the Geoportal import runs on its own controller with its own answer to give.
 *
 * <p>The job and the reader are already cleaned up by the time this runs -- see
 * {@code ImportController.refuse}. This advice only decides what the client is told.
 */
@RestControllerAdvice(assignableTypes = ImportController.class)
// Ahead of ProblemDetailAdvice, whose handler for Exception would otherwise claim this
// one first and label it a server fault.
@Order(Ordered.HIGHEST_PRECEDENCE)
class ImportOverloadAdvice {

	/** Long enough for a running import to make room, short enough to still be a retry. */
	private static final String RETRY_AFTER_SECONDS = "30";

	@ExceptionHandler(RejectedExecutionException.class)
	ResponseEntity<ProblemDetail> handleRejected(RejectedExecutionException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"Es laufen bereits zu viele Importe. Starten Sie den Import in einem Moment erneut.");
		problem.setTitle("Server ausgelastet");

		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
				.body(problem);
	}
}
