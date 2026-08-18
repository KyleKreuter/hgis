package de.kreuter.hgis.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.catalog.dto.LayerDtos;
import de.kreuter.hgis.catalog.dto.ProjectDtos;
import de.kreuter.hgis.common.ClientId;
import de.kreuter.hgis.common.JsonFields;
import de.kreuter.hgis.common.SqlIdentifier;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The live channel's second kind of event end to end (plan "Der Live-Kanal meldet auch
 * Datenaenderungen"): a write to a project's layer list, a layer's properties or a
 * field reaches every open channel as a {@code project-catalog} event, on the same two
 * rules {@link EventStreamControllerTest} already pins down for the workspace event --
 * a state, never a change, and no working data.
 *
 * <p>Setup that must not itself produce an event -- creating the layer a test then
 * reorders or adds a field to -- goes straight through {@link LayerRepository}, bypassing
 * {@code LayerService} entirely, so the one event each test looks for is never lost among
 * events the setup itself triggered.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CatalogEventStreamTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private JdbcClient jdbc;

	private Project project;

	private final List<MvcResult> openStreams = new ArrayList<>();

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Katalog-Kanal-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void tearDown() {
		openStreams.forEach(stream -> stream.getRequest().getAsyncContext().complete());
		openStreams.clear();
		layerRepository.findByProjectOrdered(project.getId())
				.forEach(l -> jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(l.getTableName()))
						.update());
		projectRepository.deleteById(project.getId());
	}

	// --- one write, one event ------------------------------------------------------------

	@Test
	@DisplayName("creating a layer reaches an open channel with the writer's name and a positive version")
	void creatingALayerReachesAnOpenChannel() throws Exception {
		MvcResult stream = openStream();

		createLayer("cli-create").andExpect(status().isCreated());

		JsonNode event = onlyEvent(stream);
		assertThat(event.get("projectId").stringValue()).isEqualTo(project.getId().toString());
		assertThat(event.get("version").asLong()).isPositive();
		assertThat(event.get("origin").stringValue()).isEqualTo("cli-create");
	}

	@Test
	@DisplayName("an event carries identifiers and numbers only -- never a layer's name or any other working data")
	void anEventCarriesNoWorkingData() throws Exception {
		MvcResult stream = openStream();

		createLayerNamed("Ganz-Besonderer-Layername-8271", null).andExpect(status().isCreated());

		JsonFields.assertFieldNames(onlyEvent(stream), "EventDtos.ProjectCatalog",
				"projectId", "version", "origin");
		assertThat(textOf(stream))
				.as("no part of the catalog itself may travel on the channel")
				.doesNotContain("Ganz-Besonderer-Layername-8271");
	}

	@Test
	@DisplayName("the event is named, so it can be told apart from a workspace event without reading it")
	void theEventIsNamed() throws Exception {
		MvcResult stream = openStream();

		createLayer(null).andExpect(status().isCreated());

		assertThat(textOf(stream)).contains("event:" + EventDtos.EventNames.PROJECT_CATALOG);
	}

	@Test
	@DisplayName("the version rises with every write, so a later event is always the newer catalog state")
	void theVersionRisesWithEveryWrite() throws Exception {
		MvcResult stream = openStream();

		createLayer(null).andExpect(status().isCreated());
		createLayer(null).andExpect(status().isCreated());

		List<JsonNode> events = catalogEventsOf(stream);
		assertThat(events).hasSize(2);
		assertThat(events.get(1).get("version").asLong()).isGreaterThan(events.get(0).get("version").asLong());
	}

	@Test
	@DisplayName("a rejected write produces no event: nothing changed, so there is nothing to report")
	void aRejectedWriteProducesNoEvent() throws Exception {
		MvcResult stream = openStream();

		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LayerDtos.CreateRequest("Ungueltig", "KEIN_GEOMETRIETYP", List.of()))))
				.andExpect(status().isBadRequest());

		assertThat(catalogEventsOf(stream)).isEmpty();
	}

	/**
	 * Found on review: {@code LayerService.update} logs {@code layer.update} to the change
	 * log unconditionally, even when every field of the request is null and nothing about
	 * the layer actually changes -- Hibernate's own dirty checking then skips the {@code
	 * UPDATE} outright, the trigger never fires, and {@code catalog_version} does not move.
	 * Without {@code CatalogEventBridge}'s own de-duplication, this PATCH would still have
	 * reached the channel a second time under the very same version it already reported.
	 */
	@Test
	@DisplayName("a PATCH that changes nothing produces no event, even though it still logs layer.update")
	void aNoOpUpdateProducesNoEvent() throws Exception {
		MvcResult createResult = createLayer(null).andExpect(status().isCreated()).andReturn();
		UUID layerId = UUID.fromString(JsonFields.tree(createResult).get("id").stringValue());

		MvcResult stream = openStream();
		mockMvc.perform(patch("/api/layers/{layerId}", layerId)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LayerDtos.UpdateRequest(null, null, null, null, null, null, null, null, null))))
				.andExpect(status().isOk());

		assertThat(catalogEventsOf(stream))
				.as("nothing about the layer changed, so nothing should have been reported")
				.isEmpty();
	}

	@Test
	@DisplayName("renaming the project itself produces no catalog event -- its own detail is not what the receiver would reread")
	void aPlainProjectWriteProducesNoCatalogEvent() throws Exception {
		MvcResult stream = openStream();

		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new ProjectDtos.UpdateRequest("Umbenannt " + UUID.randomUUID(), null, null, null,
										null, null))))
				.andExpect(status().isOk());

		assertThat(catalogEventsOf(stream))
				.as("ProjectService.update touches no layer or field -- see that class's own javadoc")
				.isEmpty();
	}

	// --- the two write paths that had no origin parameter at all before this plan --------

	@Test
	@DisplayName("reordering layers reaches the channel with the reorder's own origin")
	void reorderReachesTheChannelWithItsOwnOrigin() throws Exception {
		Layer first = createLayerDirectly();
		Layer second = createLayerDirectly();

		MvcResult stream = openStream();
		reorder(List.of(second.getId(), first.getId()), "cli-reorder").andExpect(status().isOk());

		JsonNode event = onlyEvent(stream);
		assertThat(event.get("projectId").stringValue()).isEqualTo(project.getId().toString());
		assertThat(event.get("origin").stringValue()).isEqualTo("cli-reorder");
	}

	@Test
	@DisplayName("renaming a field reaches the channel with the rename's own origin")
	void renamingAFieldReachesTheChannelWithItsOwnOrigin() throws Exception {
		// Through the real endpoint, not createLayerDirectly(): addField needs the actual
		// gis_data payload table, which only LayerService#create -- via TableCreator --
		// ever makes. Created before the stream opens so its own creation event does not
		// count towards the two this test looks for.
		MvcResult createResult = createLayer(null).andExpect(status().isCreated()).andReturn();
		UUID layerId = UUID.fromString(JsonFields.tree(createResult).get("id").stringValue());

		MvcResult stream = openStream();
		MvcResult addResult = addField(layerId, "Baujahr", "cli-add").andExpect(status().isCreated())
				.andReturn();
		UUID fieldId = UUID.fromString(JsonFields.tree(addResult).get("id").stringValue());

		renameField(layerId, fieldId, "Errichtungsjahr", "cli-rename").andExpect(status().isOk());

		List<JsonNode> events = catalogEventsOf(stream);
		assertThat(events).hasSize(2);
		assertThat(events.get(0).get("origin").stringValue()).isEqualTo("cli-add");
		assertThat(events.get(1).get("origin").stringValue()).isEqualTo("cli-rename");
	}

	// --- helpers ----------------------------------------------------------------------------

	private MvcResult openStream() throws Exception {
		MvcResult stream = mockMvc.perform(get("/api/events"))
				.andExpect(request().asyncStarted())
				.andReturn();
		openStreams.add(stream);
		return stream;
	}

	private ResultActions createLayer(String origin) throws Exception {
		return createLayerNamed("Layer " + UUID.randomUUID(), origin);
	}

	private ResultActions createLayerNamed(String name, String origin) throws Exception {
		var requestBuilder = post("/api/projects/{projectId}/layers", project.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
						new LayerDtos.CreateRequest(name, "MULTIPOINT", List.of())));
		return mockMvc.perform(withOrigin(requestBuilder, origin));
	}

	private Layer createLayerDirectly() {
		UUID layerId = UUID.randomUUID();
		return layerRepository.saveAndFlush(new Layer(layerId, project, "Layer " + layerId,
				SqlIdentifier.tableName(layerId), "MULTIPOINT", 25832));
	}

	private ResultActions reorder(List<UUID> orderedBottomToTop, String origin) throws Exception {
		var requestBuilder = put("/api/projects/{projectId}/layers/order", project.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new LayerDtos.ReorderRequest(orderedBottomToTop)));
		return mockMvc.perform(withOrigin(requestBuilder, origin));
	}

	private ResultActions addField(UUID layerId, String name, String origin) throws Exception {
		var requestBuilder = post("/api/layers/{layerId}/fields", layerId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new LayerDtos.AddFieldRequest(name, "INTEGER")));
		return mockMvc.perform(withOrigin(requestBuilder, origin));
	}

	private ResultActions renameField(UUID layerId, UUID fieldId, String newName, String origin) throws Exception {
		var requestBuilder = patch("/api/layers/{layerId}/fields/{fieldId}", layerId, fieldId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new LayerDtos.RenameFieldRequest(newName)));
		return mockMvc.perform(withOrigin(requestBuilder, origin));
	}

	private static MockHttpServletRequestBuilder withOrigin(MockHttpServletRequestBuilder builder, String origin) {
		return origin == null ? builder : builder.header(ClientId.HEADER, origin);
	}

	private static String textOf(MvcResult stream) throws UnsupportedEncodingException {
		return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private JsonNode onlyEvent(MvcResult stream) throws Exception {
		List<JsonNode> events = catalogEventsOf(stream);
		assertThat(events).as("the channel should have carried exactly one catalog event").hasSize(1);
		return events.get(0);
	}

	/**
	 * The data of every {@code project-catalog} event in the stream, parsed -- the wire
	 * format itself, the same way {@link EventStreamControllerTest#eventsOf} reads it,
	 * rather than a Spring abstraction over it.
	 */
	private List<JsonNode> catalogEventsOf(MvcResult stream) throws Exception {
		List<JsonNode> events = new ArrayList<>();
		for (String block : textOf(stream).split("\n\n")) {
			boolean isCatalogEvent = false;
			String data = null;
			for (String line : block.split("\n")) {
				if (line.startsWith("event:")) {
					isCatalogEvent = line.substring("event:".length()).trim()
							.equals(EventDtos.EventNames.PROJECT_CATALOG);
				}
				else if (line.startsWith("data:")) {
					data = line.substring("data:".length());
				}
			}
			if (isCatalogEvent && data != null) {
				events.add(objectMapper.readTree(data));
			}
		}
		return events;
	}
}
