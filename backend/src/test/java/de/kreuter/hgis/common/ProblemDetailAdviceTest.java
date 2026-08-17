package de.kreuter.hgis.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.kreuter.hgis.catalog.ProjectController;
import de.kreuter.hgis.catalog.ProjectService;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 moved this out of ...boot.test.autoconfigure.web.servlet
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Guards the error mapping.
 *
 * These cases exist because a catch-all {@code @ExceptionHandler(Exception.class)}
 * happily swallows Spring's own exceptions -- which already carry a correct status --
 * and relabels every one of them as 500. That turned every missing route and every
 * wrong method into a phantom server fault, and cost a parallel track debugging time.
 */
@WebMvcTest(controllers = ProjectController.class)
@Import(ProblemDetailAdvice.class)
class ProblemDetailAdviceTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private ProjectService projectService;

	private ListAppender<ILoggingEvent> logEvents;

	@BeforeEach
	void captureLog() {
		logEvents = new ListAppender<>();
		logEvents.start();
		((Logger) LoggerFactory.getLogger(ProblemDetailAdvice.class)).addAppender(logEvents);
	}

	@AfterEach
	void stopCapturingLog() {
		((Logger) LoggerFactory.getLogger(ProblemDetailAdvice.class)).detachAppender(logEvents);
	}

	@Test
	@DisplayName("wrong HTTP method yields 405, not 500")
	void wrongMethodIsMethodNotAllowed() throws Exception {
		mvc.perform(put("/api/projects"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.status").value(405));
	}

	@Test
	@DisplayName("malformed JSON yields 400, not 500")
	void malformedBodyIsBadRequest() throws Exception {
		mvc.perform(post("/api/projects")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{kaputt"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	@DisplayName("malformed UUID in the path yields 400, not 500")
	void malformedUuidIsBadRequest() throws Exception {
		mvc.perform(get("/api/projects/keine-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	@DisplayName("domain not-found yields 404 with a readable detail")
	void notFoundIsMapped() throws Exception {
		org.mockito.BDDMockito
				.given(projectService.get(org.mockito.ArgumentMatchers.any()))
				.willThrow(new NotFoundException("Projekt existiert nicht"));

		mvc.perform(get("/api/projects/019fec35-6373-76e1-b5a4-26943cb0a780"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("Projekt existiert nicht"));
	}

	@Test
	@DisplayName("Hibernate's own optimistic-locking failure yields 409, not 500 -- the same shape as a "
			+ "hand-rolled conflict, not the catch-all's \"Interner Fehler\"")
	void optimisticLockingFailureIsMappedToConflict() throws Exception {
		org.mockito.BDDMockito
				.given(projectService.get(org.mockito.ArgumentMatchers.any()))
				.willThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
						"de.kreuter.hgis.catalog.Layer", "019fec35-6373-76e1-b5a4-26943cb0a780"));

		mvc.perform(get("/api/projects/019fec35-6373-76e1-b5a4-26943cb0a780"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));
	}

	@Test
	@DisplayName("an unexpected failure still yields 500 without leaking internals")
	void unexpectedFailureStaysGeneric() throws Exception {
		org.mockito.BDDMockito
				.given(projectService.get(org.mockito.ArgumentMatchers.any()))
				.willThrow(new IllegalStateException("Verbindung zu db-host-7 verloren"));

		mvc.perform(get("/api/projects/019fec35-6373-76e1-b5a4-26943cb0a780"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.detail").value("Das Programm kann die Anfrage nicht verarbeiten"))
				// The internal message must not reach the client.
				.andExpect(jsonPath("$.detail").value(
						org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("db-host-7"))));
	}

	/**
	 * A client that vanished mid-response (tiles: CONTRACT.md tile size finding, a
	 * browser scrolling away from a large tile) is the ordinary case, not a server
	 * fault, and must not be logged like one. Checked against the log itself, not just
	 * the HTTP status: {@code mvc.perform} cannot reproduce the actual failure this
	 * guards against, because {@link ProblemDetailAdvice#handleUnexpected} would also
	 * happily return a 500 here -- the response only fails to convert for real once a
	 * write already committed a different content type (a tile's
	 * {@code application/vnd.mapbox-vector-tile}), which no MockMvc response ever does.
	 * The log record this handler leaves behind is the one thing that does tell the two
	 * implementations apart, so that is what this test pins down: info level, and no
	 * stack trace attached -- the frames underneath are Tomcat's write plumbing, not a
	 * fault in this code, and would only bury a real error logged next to it.
	 */
	@Test
	@DisplayName("a client that disconnected mid-response is logged at info, without a stack trace")
	void clientGoneDuringResponseIsLoggedQuietly() throws Exception {
		// willThrow() would reject this: AsyncRequestNotUsableException extends the
		// checked IOException, which ProjectService.get does not declare. willAnswer
		// throws it the same way a real write failure would -- as an unchecked wrapper
		// somewhere below this call, not as a checked exception the compiler tracked.
		org.mockito.BDDMockito
				.given(projectService.get(org.mockito.ArgumentMatchers.any()))
				.willAnswer(invocation -> {
					throw new AsyncRequestNotUsableException(
							"ServletOutputStream failed to write", new IOException("Broken pipe"));
				});

		mvc.perform(get("/api/projects/019fec35-6373-76e1-b5a4-26943cb0a780"));

		org.assertj.core.api.Assertions.assertThat(logEvents.list).hasSize(1);
		ILoggingEvent event = logEvents.list.get(0);
		org.assertj.core.api.Assertions.assertThat(event.getLevel()).isEqualTo(Level.INFO);
		org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy())
				.as("kein Stacktrace fuer einen abgebrochenen Client -- das waere die Tomcat-Schreib-Kette, kein eigener Fehler")
				.isNull();
	}
}
