package de.kreuter.hgis.common;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single place that turns exceptions into RFC 7807 responses, so the frontend can rely
 * on one error shape everywhere: {type, title, status, detail} plus optional "errors"
 * for per-field validation messages.
 */
@RestControllerAdvice
public class ProblemDetailAdvice {

	private static final Logger log = LoggerFactory.getLogger(ProblemDetailAdvice.class);

	@ExceptionHandler(NotFoundException.class)
	public ProblemDetail handleNotFound(NotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Nicht gefunden", ex.getMessage());
	}

	@ExceptionHandler(BadRequestException.class)
	public ProblemDetail handleBadRequest(BadRequestException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Ungültige Anfrage", ex.getMessage());
	}

	/** Concurrent modification. The current server state travels along so the UI can show
	 *  the difference rather than only reporting that there is one. */
	@ExceptionHandler(ConflictException.class)
	public ProblemDetail handleConflict(ConflictException ex) {
		ProblemDetail problem = problem(HttpStatus.CONFLICT, "Konflikt", ex.getMessage());
		if (ex.getCurrent() != null) {
			problem.setProperty("current", ex.getCurrent());
		}
		return problem;
	}

	/**
	 * Hibernate's own optimistic check -- a row this request loaded was changed or
	 * removed by someone else before this request's write reached it -- not a client
	 * -supplied {@code rowVersion} mismatch, which is already a {@link ConflictException}
	 * of its own. A client cannot tell the two apart and should not have to: same 409
	 * shape as every hand-rolled conflict here, not the catch-all's "Interner Fehler".
	 * Concretely reachable from two racing trash state transitions (delete/restore/purge)
	 * on the same layer -- {@code LayerRepository#findByIdForUpdate} closes the window
	 * where the race would otherwise happen, but this stays as the honest answer for
	 * whatever narrower race the row lock does not cover.
	 */
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ProblemDetail handleOptimisticLocking(ObjectOptimisticLockingFailureException ex) {
		return problem(HttpStatus.CONFLICT, "Konflikt",
				"Eine andere Stelle hat diesen Datensatz zwischenzeitlich geändert oder entfernt");
	}

	/** The resource exists and answered, but its content cannot be used as asked. */
	@ExceptionHandler(UnprocessableEntityException.class)
	public ProblemDetail handleUnprocessable(UnprocessableEntityException ex) {
		return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Nicht verarbeitbar", ex.getMessage());
	}

	/** Bean validation failures, reported per field so the form can highlight them. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Eingabe ungültig",
				"Die Anfrage enthält ungültige Felder");
		problem.setProperty("errors", errors);
		return problem;
	}

	/**
	 * A business rule caught what {@code @Valid} could not -- an enum token, a
	 * cross-field duplicate check. Same "errors" shape as {@link #handleValidation}, one
	 * entry, so the frontend's per-field lookup works the same regardless of which
	 * layer rejected the value.
	 */
	@ExceptionHandler(FieldValidationException.class)
	public ProblemDetail handleFieldValidation(FieldValidationException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Eingabe ungültig", ex.getMessage());
		problem.setProperty("errors", Map.of(ex.getField(), ex.getMessage()));
		return problem;
	}

	/** A malformed UUID in the path is a client error, not a server fault. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Ungültiger Parameter",
				"'" + ex.getName() + "' hat kein gültiges Format");
	}

	/** Unknown path. Without this it would fall through to the catch-all and read as 500. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ProblemDetail handleNoResource(NoResourceFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Nicht gefunden",
				"Die Ressource '" + ex.getResourcePath() + "' existiert nicht");
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
		return problem(HttpStatus.METHOD_NOT_ALLOWED, "Methode nicht erlaubt",
				ex.getMethod() + " ist für diese Ressource nicht vorgesehen");
	}

	/** Malformed JSON or a body that cannot be bound is a client error, not a server fault. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
		// The bare message says only that something in the body did not fit, which leaves
		// no way to find out what. The parser's own reason names the field and the reason,
		// and it is logged as well: a request nobody can read must not also be a request
		// nobody can diagnose.
		String cause = ex.getMostSpecificCause().getMessage();
		log.debug("Unreadable request body", ex);

		return problem(HttpStatus.BAD_REQUEST, "Ungültige Anfrage",
				cause == null || cause.isBlank()
						? "Das Programm kann den Anfragekörper nicht lesen"
						: "Das Programm kann den Anfragekörper nicht lesen. Grund: " + firstLine(cause));
	}

	/** Jackson appends the parse position over several lines; the first one carries the reason. */
	private static String firstLine(String message) {
		int newline = message.indexOf('\n');
		return newline < 0 ? message : message.substring(0, newline);
	}

	/**
	 * The client is gone before a response finished writing -- a browser that scrolled
	 * away mid-tile-fetch and aborted the request is the ordinary case, not a server
	 * fault (tiles: CONTRACT.md tile size finding). Spring wraps it, whichever write
	 * failed, in this one type, so one handler covers it for every endpoint.
	 *
	 * <p>Two things a generic {@code Exception} handler would get wrong here. First, the
	 * stack trace: it is Tomcat's write plumbing underneath a broken socket, not a fault
	 * in this code, so it is logged at info and without one -- an {@code ERROR} full of
	 * frames for an ordinary disconnect is exactly the noise that buries a real fault
	 * next to it. Second, and the reason this handler returns {@code void} rather than a
	 * {@link ProblemDetail} like every other one here: by the time this fires, the
	 * response has typically already committed a content type from the failed write --
	 * {@code application/vnd.mapbox-vector-tile} for a tile -- and no converter turns a
	 * {@code ProblemDetail} into that. Trying anyway does not reach the client (the
	 * socket is already gone) and only replaces this clear cause in the log with
	 * Spring's own "no converter for preset Content-Type" failure. A {@code void} return
	 * is Spring's own signal that the response needs nothing further, so that second
	 * failure never happens.
	 */
	@ExceptionHandler(AsyncRequestNotUsableException.class)
	public void handleClientGone(AsyncRequestNotUsableException ex) {
		log.info("Antwort nicht mehr zustellbar, Client hat die Verbindung beendet: {}", ex.getMessage());
	}

	/** PostgreSQL's SQLSTATE class "22" code for a value that overflows its column's declared
	 *  precision/scale, e.g. {@code numeric(12,2)}. */
	private static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";

	/**
	 * PostgreSQL rejects a numeric value that does not fit its column's declared precision
	 * or scale with a plain {@code numeric field overflow} (SQLSTATE 22003) -- a review
	 * found this fell through to {@link #handleUnexpected} with no field named and no
	 * reason given, the same failure class the NaN/Infinity fix in this package closed,
	 * just at a spot an everyday typo or a wrongly-scaled import value hits far more often
	 * than a special value ever would.
	 *
	 * <p>Deliberately without a field name: {@code EditService} binds one statement per
	 * create/update that can touch several columns at once, and neither PostgreSQL's error
	 * message nor its SQLSTATE says which one overflowed -- naming one would mean guessing,
	 * which is worse than naming none. Every other {@link DataIntegrityViolationException}
	 * (a NOT NULL, a unique or a foreign key violation) is a different SQLSTATE and keeps
	 * falling through to {@link #handleUnexpected} exactly as before.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		if (ex.getMostSpecificCause() instanceof SQLException sqlEx
				&& NUMERIC_VALUE_OUT_OF_RANGE.equals(sqlEx.getSQLState())) {
			return problem(HttpStatus.BAD_REQUEST, "Ungültige Anfrage",
					"Ein Zahlenwert ist zu groß oder hat zu viele Nachkommastellen für sein Feld");
		}
		return handleUnexpected(ex);
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception ex) {
		// Spring's own exceptions already carry a correct status and body. Catching
		// Exception is broad enough to swallow them, which would turn every 404, 405 or
		// 415 into a 500 -- so hand those straight back instead of relabelling them.
		if (ex instanceof ErrorResponse errorResponse) {
			return errorResponse.getBody();
		}
		// Log with stack trace, but never leak internals to the client.
		log.error("Unhandled exception", ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Interner Fehler",
				"Das Programm kann die Anfrage nicht verarbeiten");
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}
}
