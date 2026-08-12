package de.kreuter.hgis.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.catalog.ProjectController;
import de.kreuter.hgis.catalog.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 moved this out of ...boot.test.autoconfigure.web.servlet
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
	@DisplayName("an unexpected failure still yields 500 without leaking internals")
	void unexpectedFailureStaysGeneric() throws Exception {
		org.mockito.BDDMockito
				.given(projectService.get(org.mockito.ArgumentMatchers.any()))
				.willThrow(new IllegalStateException("Verbindung zu db-host-7 verloren"));

		mvc.perform(get("/api/projects/019fec35-6373-76e1-b5a4-26943cb0a780"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.detail").value("Das Programm kann die Anfrage nicht verarbeiten."))
				// The internal message must not reach the client.
				.andExpect(jsonPath("$.detail").value(
						org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("db-host-7"))));
	}
}
