package de.kreuter.hgis.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.catalog.dto.ProjectDtos;
import de.kreuter.hgis.common.ClientId;
import de.kreuter.hgis.common.JsonFields;
import de.kreuter.hgis.events.dto.EventDtos;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TASKS.md Aufgabe 9 end to end: {@code set_view} (in effect {@code PATCH
 * /api/projects/{id}} with a new {@code center}/{@code zoom}) reaches every open live
 * channel as a {@code project-viewport} event -- the piece that was missing before this
 * plan, which is why an already-open tab never moved on its own.
 *
 * <p>Same two rules as {@link CatalogEventStreamTest} and {@link EventStreamControllerTest}
 * -- a state, never a change, and no working data -- plus the one this event adds on top
 * of them: it must stay rare. {@code ProjectDtos.UpdateRequest} also carries {@code
 * name}, {@code description}, {@code basemap} and {@code basemapOpacity}, and none of
 * those may wake an open tab's map.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectViewportEventStreamTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ProjectRepository projectRepository;

	private Project project;

	private final List<MvcResult> openStreams = new ArrayList<>();

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Ausschnitt-Kanal-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void tearDown() {
		openStreams.forEach(stream -> stream.getRequest().getAsyncContext().complete());
		openStreams.clear();
		projectRepository.deleteById(project.getId());
	}

	@Test
	@DisplayName("a new center/zoom reaches an open channel with the writer's name")
	void newCenterAndZoomReachAnOpenChannel() throws Exception {
		MvcResult stream = openStream();

		setView(new double[] { 10.0, 53.55 }, 12.0, "cli-set-view").andExpect(status().isOk());

		JsonNode event = onlyEvent(stream);
		assertThat(event.get("projectId").stringValue()).isEqualTo(project.getId().toString());
		assertThat(event.get("origin").stringValue()).isEqualTo("cli-set-view");
	}

	@Test
	@DisplayName("the event is named, so it can be told apart from the other two without reading it")
	void theEventIsNamed() throws Exception {
		MvcResult stream = openStream();

		setView(new double[] { 9.0, 48.0 }, 10.0, null).andExpect(status().isOk());

		assertThat(textOf(stream)).contains("event:" + EventDtos.EventNames.PROJECT_VIEWPORT);
	}

	@Test
	@DisplayName("an event carries identifiers and the origin only -- never the coordinates themselves")
	void anEventCarriesNoWorkingData() throws Exception {
		MvcResult stream = openStream();

		setView(new double[] { 8.5, 47.5 }, 14.0, null).andExpect(status().isOk());

		JsonFields.assertFieldNames(onlyEvent(stream), "EventDtos.ProjectViewport", "projectId", "origin");
		assertThat(textOf(stream)).as("no coordinate may travel on the channel itself")
				.doesNotContain("8.5").doesNotContain("47.5");
	}

	/**
	 * The condition the plan names explicitly (TASKS.md Aufgabe 9, "Sparsamkeit"): a
	 * project's own name, description, basemap and opacity travel through the very same
	 * {@code PATCH}, and none of them may move a map nobody touched.
	 */
	@Test
	@DisplayName("renaming the project -- no center/zoom in the request at all -- produces no viewport event")
	void aPlainRenameProducesNoViewportEvent() throws Exception {
		MvcResult stream = openStream();

		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"Umbenannt " + UUID.randomUUID() + "\"}"))
				.andExpect(status().isOk());

		assertThat(viewportEventsOf(stream))
				.as("ProjectService.update fires this event for center/zoom alone")
				.isEmpty();
	}

	@Test
	@DisplayName("changing the basemap alone produces no viewport event either")
	void aBasemapChangeAloneProducesNoViewportEvent() throws Exception {
		MvcResult stream = openStream();

		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						// A real basemap token since Befund 1 (Validierung, 27.08.) -- an
						// unknown one such as the previous "satellite" is now a 400.
						.content("{\"basemap\": \"opentopo\"}"))
				.andExpect(status().isOk());

		assertThat(viewportEventsOf(stream)).isEmpty();
	}

	@Test
	@DisplayName("resending the same center/zoom the project already stands at produces no second event")
	void resendingTheSameViewportProducesNoEvent() throws Exception {
		double[] center = { 11.0, 49.0 };
		setView(center, 9.0, null).andExpect(status().isOk());

		MvcResult stream = openStream();
		setView(center, 9.0, null).andExpect(status().isOk());

		assertThat(viewportEventsOf(stream))
				.as("nothing about the viewport changed, so nothing should have been reported")
				.isEmpty();
	}

	@Test
	@DisplayName("a rejected write produces no event: nothing changed, so there is nothing to report")
	void aRejectedWriteProducesNoEvent() throws Exception {
		MvcResult stream = openStream();

		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"center\": [200.0, 0.0]}"))
				.andExpect(status().isBadRequest());

		assertThat(viewportEventsOf(stream)).isEmpty();
	}

	// --- helpers ----------------------------------------------------------------------------

	private MvcResult openStream() throws Exception {
		MvcResult stream = mockMvc.perform(get("/api/events"))
				.andExpect(request().asyncStarted())
				.andReturn();
		openStreams.add(stream);
		return stream;
	}

	private ResultActions setView(double[] center, double zoom, String origin) throws Exception {
		String body = objectMapper.writeValueAsString(
				new ProjectDtos.UpdateRequest(null, null, null, null, center, zoom));
		MockHttpServletRequestBuilder builder = patch("/api/projects/{id}", project.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
		if (origin != null) {
			builder = builder.header(ClientId.HEADER, origin);
		}
		return mockMvc.perform(builder);
	}

	private static String textOf(MvcResult stream) throws UnsupportedEncodingException {
		return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private JsonNode onlyEvent(MvcResult stream) throws Exception {
		List<JsonNode> events = viewportEventsOf(stream);
		assertThat(events).as("the channel should have carried exactly one viewport event").hasSize(1);
		return events.get(0);
	}

	/** The data of every {@code project-viewport} event in the stream, parsed off the wire. */
	private List<JsonNode> viewportEventsOf(MvcResult stream) throws Exception {
		List<JsonNode> events = new ArrayList<>();
		for (String block : textOf(stream).split("\n\n")) {
			boolean isViewportEvent = false;
			String data = null;
			for (String line : block.split("\n")) {
				if (line.startsWith("event:")) {
					isViewportEvent = line.substring("event:".length()).trim()
							.equals(EventDtos.EventNames.PROJECT_VIEWPORT);
				}
				else if (line.startsWith("data:")) {
					data = line.substring("data:".length());
				}
			}
			if (isViewportEvent && data != null) {
				events.add(objectMapper.readTree(data));
			}
		}
		return events;
	}
}
