package de.kreuter.hgis.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.catalog.dto.ProjectDtos;
import de.kreuter.hgis.common.ClientId;
import de.kreuter.hgis.common.JsonFields;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.events.dto.EventDtos;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The live channel end to end: a working state is written, and whoever holds an open
 * {@code GET /api/events} hears where that project now stands.
 *
 * <p>The two rules the whole channel rests on are what this class is mostly about --
 * an event reports a state and never a change, and it carries no working data at all.
 * The second one is checked by naming the event's complete field set, not by reading a
 * few fields that happen to be right (see {@link JsonFields} for why that difference
 * matters).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EventStreamControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private EventStreams eventStreams;

	private Project project;

	/** Every stream this test opened, so it can hand the slots back -- see {@link #closeStreams}. */
	private final List<MvcResult> openStreams = new ArrayList<>();

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Live-Kanal-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void closeStreams() {
		// Nothing else ends these: a MockMvc request stays async until something completes
		// it. Left open they would still be registered when the next test class runs, and
		// the registry would slowly fill up over a whole suite.
		openStreams.forEach(stream -> stream.getRequest().getAsyncContext().complete());
		openStreams.clear();
		projectRepository.deleteById(project.getId());
	}

	// --- the stream itself -------------------------------------------------------------

	@Test
	@DisplayName("the channel answers as an event stream and says how long to wait before reconnecting")
	void theChannelOpensAsAnEventStream() throws Exception {
		MvcResult stream = openStream();

		assertThat(stream.getResponse().getStatus()).isEqualTo(200);
		assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
		assertThat(textOf(stream)).contains("retry:");
	}

	@Test
	@DisplayName("a finished request gives its slot back")
	void aFinishedRequestFreesItsSlot() throws Exception {
		int before = eventStreams.openStreams();
		MvcResult stream = openStream();
		assertThat(eventStreams.openStreams()).isEqualTo(before + 1);

		openStreams.remove(stream);
		stream.getRequest().getAsyncContext().complete();

		assertThat(eventStreams.openStreams()).isEqualTo(before);
	}

	// --- what an event says ------------------------------------------------------------

	@Test
	@DisplayName("saving a working state tells every open channel which project moved, and to which version")
	void savingAWorkingStateReachesAnOpenChannel() throws Exception {
		MvcResult stream = openStream();

		putViewState(someViewState(), null).andExpect(status().isNoContent());

		JsonNode event = onlyEvent(stream);
		assertThat(event.get("projectId").stringValue()).isEqualTo(project.getId().toString());
		assertThat(event.get("version").asLong()).isPositive();
	}

	@Test
	@DisplayName("the version rises with every write, so a later event is always the newer state")
	void theVersionRisesWithEveryWrite() throws Exception {
		MvcResult stream = openStream();

		putViewState(someViewState(), null).andExpect(status().isNoContent());
		putViewState(someViewState(), null).andExpect(status().isNoContent());

		List<JsonNode> events = eventsOf(stream);
		assertThat(events).hasSize(2);
		assertThat(events.get(1).get("version").asLong()).isGreaterThan(events.get(0).get("version").asLong());
	}

	@Test
	@DisplayName("an event carries identifiers and numbers only -- never the working state itself")
	void anEventCarriesNoWorkingData() throws Exception {
		MvcResult stream = openStream();
		Layer layer = createLayer();
		// Values distinctive enough that finding any of them in the stream can only mean
		// the working state itself travelled along. The fid is eight digits rather than a
		// short one so it cannot turn up by chance inside the project's own hex id.
		ProjectDtos.ViewState state = new ProjectDtos.ViewState(1, layer.getId(), Map.of(layer.getId(),
				new ProjectDtos.LayerViewState(
						new ProjectDtos.Sort("baujahr", true),
						new ProjectDtos.Query(ProjectDtos.QUERY_MODE_SEARCH, "Rautenberg"),
						List.of(98765432L))));

		putViewState(state, null).andExpect(status().isNoContent());

		JsonFields.assertFieldNames(onlyEvent(stream), "EventDtos.ProjectViewState",
				"projectId", "version", "origin");
		assertThat(textOf(stream))
				.as("no part of the working state may travel on the channel")
				.doesNotContain("baujahr", "Rautenberg", "98765432", "selection", "sort", "layers",
						layer.getId().toString());
	}

	@Test
	@DisplayName("the event is named, so a second kind of event can be told apart without reading it")
	void theEventIsNamed() throws Exception {
		MvcResult stream = openStream();

		putViewState(someViewState(), null).andExpect(status().isNoContent());

		assertThat(textOf(stream)).contains("event:" + EventDtos.EventNames.PROJECT_VIEW_STATE);
	}

	// --- hearing your own change --------------------------------------------------------

	@Test
	@DisplayName("the writer's own name comes back, so it can tell its own echo from someone else's change")
	void theWritersOwnNameComesBack() throws Exception {
		MvcResult stream = openStream();

		putViewState(someViewState(), "tab-42").andExpect(status().isNoContent());

		assertThat(onlyEvent(stream).get("origin").stringValue()).isEqualTo("tab-42");
	}

	@Test
	@DisplayName("a writer that names nobody produces an event without an origin, not a failed write")
	void aWriterMayStayAnonymous() throws Exception {
		MvcResult stream = openStream();

		putViewState(someViewState(), null).andExpect(status().isNoContent());

		assertThat(onlyEvent(stream).get("origin").isNull()).isTrue();
	}

	@Test
	@DisplayName("a name that cannot be echoed as-is is refused rather than quietly dropped")
	void aMalformedClientNameIsRefused() throws Exception {
		putViewState(someViewState(), "tab 42 <script>").andExpect(status().isBadRequest());
	}

	// --- when the write does not happen ---------------------------------------------------

	@Test
	@DisplayName("a rejected write produces no event: nothing changed, so there is nothing to report")
	void aRejectedWriteProducesNoEvent() throws Exception {
		MvcResult stream = openStream();
		Layer layer = createLayer();
		ProjectDtos.ViewState invalid = new ProjectDtos.ViewState(1, null, Map.of(layer.getId(),
				new ProjectDtos.LayerViewState(null, new ProjectDtos.Query("weder-noch", "x"), List.of())));

		putViewState(invalid, null).andExpect(status().isBadRequest());

		assertThat(eventsOf(stream)).isEmpty();
	}

	// --- helpers ----------------------------------------------------------------------------

	private MvcResult openStream() throws Exception {
		MvcResult stream = mockMvc.perform(get("/api/events"))
				.andExpect(request().asyncStarted())
				.andReturn();
		openStreams.add(stream);
		return stream;
	}

	private Layer createLayer() {
		UUID layerId = UUID.randomUUID();
		return layerRepository.saveAndFlush(new Layer(layerId, project, "Layer " + layerId,
				SqlIdentifier.tableName(layerId), "MULTIPOINT", 25832));
	}

	private ProjectDtos.ViewState someViewState() {
		return new ProjectDtos.ViewState(1, null, Map.of());
	}

	private ResultActions putViewState(ProjectDtos.ViewState state, String clientId) throws Exception {
		var request = put("/api/projects/{id}/view-state", project.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(state));
		if (clientId != null) {
			request = request.header(ClientId.HEADER, clientId);
		}
		return mockMvc.perform(request);
	}

	/** Everything the stream has sent so far. Never blocks -- what is not there yet is not there. */
	private static String textOf(MvcResult stream) throws UnsupportedEncodingException {
		return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private JsonNode onlyEvent(MvcResult stream) throws Exception {
		List<JsonNode> events = eventsOf(stream);
		assertThat(events).as("the channel should have carried exactly one event").hasSize(1);
		return events.get(0);
	}

	/**
	 * The data of every {@code project-view-state} event in the stream, parsed.
	 *
	 * <p>Reads the wire format rather than a Spring abstraction on purpose: the format is
	 * what {@code curl -N} shows and what an {@code EventSource} parses, so it is the thing
	 * worth pinning down. Blocks separated by an empty line, fields as {@code name:value}.
	 */
	private List<JsonNode> eventsOf(MvcResult stream) throws Exception {
		List<JsonNode> events = new ArrayList<>();
		for (String block : textOf(stream).split("\n\n")) {
			boolean isViewState = false;
			String data = null;
			for (String line : block.split("\n")) {
				if (line.startsWith("event:")) {
					isViewState = line.substring("event:".length()).trim()
							.equals(EventDtos.EventNames.PROJECT_VIEW_STATE);
				}
				else if (line.startsWith("data:")) {
					data = line.substring("data:".length());
				}
			}
			if (isViewState && data != null) {
				events.add(objectMapper.readTree(data));
			}
		}
		return events;
	}
}
