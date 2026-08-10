package de.kreuter.hgis.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

	/** A malformed UUID in the path is a client error, not a server fault. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Ungültiger Parameter",
				"'" + ex.getName() + "' hat kein gültiges Format");
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception ex) {
		// Log with stack trace, but never leak internals to the client.
		log.error("Unhandled exception", ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Interner Fehler",
				"Die Anfrage konnte nicht verarbeitet werden");
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}
}
