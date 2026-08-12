package de.kreuter.hgis.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.dto.ProjectDtos;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * The project's view state (CONTRACT.md phase 17, package A2): what the client left open
 * when it last left a project -- active layer, and per layer sort, query and selection.
 *
 * <p>The one rule the whole feature rests on is the cleanup that happens on read: a
 * layer's entry in {@code layers} and a stale {@code activeLayerId} both have to fall
 * away the moment the layer itself is gone, without either side needing a cleanup step
 * of its own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectViewStateTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	private Project project;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Arbeitsstand-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void tearDown() {
		projectRepository.deleteById(project.getId());
	}

	// --- saving and reading -------------------------------------------------------------

	@Test
	@DisplayName("a saved view state comes back exactly as it was written")
	void savingAndReadingRoundTrips() throws Exception {
		Layer layer = createLayer();

		ProjectDtos.ViewState saved = new ProjectDtos.ViewState(1, layer.getId(), Map.of(layer.getId(),
				new ProjectDtos.LayerViewState(
						new ProjectDtos.Sort("baujahr", true),
						new ProjectDtos.Query(ProjectDtos.QUERY_MODE_SEARCH, "Schmidt"),
						List.of(12L, 47L, 199L))));

		putViewState(saved).andExpect(status().isNoContent());

		getViewState()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.activeLayerId").value(layer.getId().toString()))
				.andExpect(jsonPath(layerPath(layer, "sort.field")).value("baujahr"))
				.andExpect(jsonPath(layerPath(layer, "sort.desc")).value(true))
				.andExpect(jsonPath(layerPath(layer, "query.mode")).value("search"))
				.andExpect(jsonPath(layerPath(layer, "query.text")).value("Schmidt"))
				.andExpect(jsonPath(layerPath(layer, "selection"), Matchers.contains(12, 47, 199)));
	}

	@Test
	@DisplayName("a project that was never saved answers with the empty document, not 404")
	void aProjectWithoutASavedStateAnswersWithTheEmptyDocument() throws Exception {
		getViewState()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.activeLayerId").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.layers").isEmpty());
	}

	@Test
	void aMissingProjectIs404ForBothEndpoints() throws Exception {
		UUID missing = UUID.randomUUID();
		mockMvc.perform(get("/api/projects/{id}/view-state", missing)).andExpect(status().isNotFound());
		mockMvc.perform(put("/api/projects/{id}/view-state", missing)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(ProjectDtos.ViewState.empty())))
				.andExpect(status().isNotFound());
	}

	// --- cleanup on read ------------------------------------------------------------------

	@Test
	@DisplayName("a deleted layer falls out of the layers map when the state is read")
	void aDeletedLayerFallsOutOfTheLayersMap() throws Exception {
		Layer layer = createLayer();
		ProjectDtos.ViewState saved = new ProjectDtos.ViewState(1, null,
				Map.of(layer.getId(), new ProjectDtos.LayerViewState(null, null, List.of(1L, 2L, 3L))));
		putViewState(saved).andExpect(status().isNoContent());

		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId())).andExpect(status().isNoContent());

		getViewState()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.layers").isEmpty());
	}

	@Test
	@DisplayName("activeLayerId pointing at a deleted layer becomes null when the state is read")
	void activeLayerIdOnADeletedLayerBecomesNull() throws Exception {
		Layer layer = createLayer();
		ProjectDtos.ViewState saved = new ProjectDtos.ViewState(1, layer.getId(), Map.of());
		putViewState(saved).andExpect(status().isNoContent());

		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId())).andExpect(status().isNoContent());

		getViewState()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activeLayerId").value(Matchers.nullValue()));
	}

	// --- rejections -------------------------------------------------------------------

	@Test
	@DisplayName("a selection above 10,000 entries is rejected and the actual count is named")
	void aSelectionAboveTheLimitIsRejected() throws Exception {
		Layer layer = createLayer();
		List<Long> tooMany = LongStream.range(0, 10_001).boxed().toList();
		ProjectDtos.ViewState saved = new ProjectDtos.ViewState(1, null,
				Map.of(layer.getId(), new ProjectDtos.LayerViewState(null, null, tooMany)));

		putViewState(saved)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail", Matchers.containsString("10001")));
	}

	@Test
	@DisplayName("exactly 10,000 selected entries is still accepted")
	void aSelectionAtTheLimitIsAccepted() throws Exception {
		Layer layer = createLayer();
		List<Long> atLimit = LongStream.range(0, 10_000).boxed().toList();
		ProjectDtos.ViewState saved = new ProjectDtos.ViewState(1, null,
				Map.of(layer.getId(), new ProjectDtos.LayerViewState(null, null, atLimit)));

		putViewState(saved).andExpect(status().isNoContent());
	}

	@Test
	void anUnknownQueryModeIsRejected() throws Exception {
		Layer layer = createLayer();
		ProjectDtos.ViewState saved = new ProjectDtos.ViewState(1, null, Map.of(layer.getId(),
				new ProjectDtos.LayerViewState(null, new ProjectDtos.Query("sort", "x"), List.of())));

		putViewState(saved).andExpect(status().isBadRequest());
	}

	@Test
	void queryTextAboveTwoThousandCharactersIsRejected() throws Exception {
		Layer layer = createLayer();
		String tooLong = "x".repeat(2001);
		ProjectDtos.ViewState saved = new ProjectDtos.ViewState(1, null, Map.of(layer.getId(),
				new ProjectDtos.LayerViewState(null, new ProjectDtos.Query(ProjectDtos.QUERY_MODE_SEARCH, tooLong),
						List.of())));

		putViewState(saved).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("sort.field is stored and returned as-is, never checked against the layer's fields")
	void sortFieldIsNotValidatedAgainstLayerFields() throws Exception {
		Layer layer = createLayer();
		ProjectDtos.ViewState saved = new ProjectDtos.ViewState(1, null, Map.of(layer.getId(),
				new ProjectDtos.LayerViewState(new ProjectDtos.Sort("existiert_nicht", false), null, List.of())));

		putViewState(saved).andExpect(status().isNoContent());
		getViewState().andExpect(status().isOk())
				.andExpect(jsonPath(layerPath(layer, "sort.field")).value("existiert_nicht"));
	}

	// --- helpers ------------------------------------------------------------------------

	private Layer createLayer() {
		UUID layerId = UUID.randomUUID();
		String tableName = SqlIdentifier.tableName(layerId);
		return layerRepository.saveAndFlush(
				new Layer(layerId, project, "Layer " + layerId, tableName, "MULTIPOINT", 25832));
	}

	private ResultActions putViewState(ProjectDtos.ViewState viewState) throws Exception {
		return mockMvc.perform(put("/api/projects/{id}/view-state", project.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(viewState)));
	}

	private ResultActions getViewState() throws Exception {
		return mockMvc.perform(get("/api/projects/{id}/view-state", project.getId()));
	}

	private String layerPath(Layer layer, String suffix) {
		return "$.layers['" + layer.getId() + "']." + suffix;
	}
}
