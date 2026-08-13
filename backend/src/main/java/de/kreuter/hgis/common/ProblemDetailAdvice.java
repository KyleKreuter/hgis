package de.kreuter.hgis.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
