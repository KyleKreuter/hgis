package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A layer can be marked as the project's clip mask (CONTRACT.md phase 19): at most one
 * per project, restricted to polygon geometry, reported through the same PATCH endpoint
 * that already carries the rest of a layer's settings. This class covers the catalog
 * side -- marking, unmarking, the second-mask-demotes-the-first rule, and clipVersion
 * reacting to a mask edit or a reorder. Whether {@code MvtService} actually cuts the
 * geometry is covered separately, by {@code tiles.MvtServiceClipTest} and
 * {@code tiles.TileControllerClipTest}.
 *
 * No physical payload tables here, same as {@link LayerReorderTest}: every test in this
 * class only touches the catalog.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LayerClipMaskTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private Project project;

	/** Bottom to top: unten, maske, oben, zweiteMaske, punktlayer. */
	private Layer unten;
	private Layer maske;
	private Layer oben;
	private Layer zweiteMaske;
	private Layer punktlayer;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Zuschnitt-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		unten = saveLayer("Unten", "MULTIPOLYGON", 0);
		maske = saveLayer("Maske", "MULTIPOLYGON", 1);
		oben = saveLayer("Oben", "MULTIPOLYGON", 2);
		zweiteMaske = saveLayer("Zweite Maske", "MULTIPOLYGON", 3);
		punktlayer = saveLayer("Punktlayer", "MULTIPOINT", 4);
	}

	@AfterEach
	void tearDown() {
		layerRepository.deleteAll(layerRepository.findByProjectOrdered(project.getId()));
		projectRepository.deleteById(project.getId());
	}

	private Layer saveLayer(String name, String geometryType, int zIndex) {
		UUID id = UUID.randomUUID();
		Layer layer = new Layer(id, project, name, SqlIdentifier.tableName(id), geometryType, 25832);
		layer.setZIndex(zIndex);
		return layerRepository.saveAndFlush(layer);
	}

	@Test
	@DisplayName("marking a polygon layer as the mask shows up in Detail, Summary and the project list")
	void marksAPolygonLayerAsTheMask() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", maske.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMask\": true }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clipMask").value(true))
				.andExpect(jsonPath("$.previousClipMaskLayerId").doesNotExist());

		mockMvc.perform(get("/api/layers/{layerId}", maske.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clipMask").value(true));

		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id=='" + maske.getId() + "')].clipMask").value(true))
				.andExpect(jsonPath("$[?(@.id=='" + oben.getId() + "')].clipMask").value(false));
	}

	@Test
	@DisplayName("unmarking the mask sets clipMask back to false")
	void unmarksTheMask() throws Exception {
		mark(maske);

		mockMvc.perform(patch("/api/layers/{layerId}", maske.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMask\": false }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clipMask").value(false));

		assertThat(layerRepository.findById(maske.getId()).orElseThrow().isClipMask()).isFalse();
	}

	@Test
	@DisplayName("a point layer is rejected as a mask with 400")
	void rejectsAPointLayerAsAMask() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", punktlayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMask\": true }"))
				.andExpect(status().isBadRequest());

		assertThat(layerRepository.findById(punktlayer.getId()).orElseThrow().isClipMask()).isFalse();
	}

	@Test
	@DisplayName("marking a second layer demotes the first and reports it")
	void aSecondMaskDemotesTheFirst() throws Exception {
		mark(maske);

		mockMvc.perform(patch("/api/layers/{layerId}", zweiteMaske.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMask\": true }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clipMask").value(true))
				.andExpect(jsonPath("$.previousClipMaskLayerId").value(maske.getId().toString()));

		assertThat(layerRepository.findById(maske.getId()).orElseThrow().isClipMask()).isFalse();
		assertThat(layerRepository.findById(zweiteMaske.getId()).orElseThrow().isClipMask()).isTrue();
	}

	@Test
	@DisplayName("a layer above the mask gets a non-zero clipVersion, one below and the mask itself stay at zero")
	void clipVersionReflectsPositionRelativeToTheMask() throws Exception {
		mark(maske);

		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id=='" + unten.getId() + "')].clipVersion").value(0))
				.andExpect(jsonPath("$[?(@.id=='" + maske.getId() + "')].clipVersion").value(0));

		long clipVersion = clipVersionOf(oben.getId());
		assertThat(clipVersion).isNotZero();
	}

	@Test
	@DisplayName("editing the mask's data changes the clipVersion of layers above it")
	void editingTheMaskChangesClipVersion() throws Exception {
		mark(maske);
		long before = clipVersionOf(oben.getId());

		bumpDataVersionInItsOwnTransaction(maske.getId());

		long after = clipVersionOf(oben.getId());
		assertThat(after).isNotEqualTo(before);
	}

	@Test
	@DisplayName("dragging a layer above the mask changes its clipVersion, with no edit to the layer itself")
	void reorderingAcrossTheMaskChangesClipVersion() throws Exception {
		mark(maske);
		assertThat(clipVersionOf(unten.getId())).isZero();

		// New bottom-to-top order: maske, unten, oben, zweiteMaske, punktlayer -- unten
		// now sits above the mask.
		mockMvc.perform(put("/api/projects/{projectId}/layers/order", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(maske.getId(), unten.getId(), oben.getId(),
								zweiteMaske.getId(), punktlayer.getId())))
				.andExpect(status().isOk());

		assertThat(clipVersionOf(unten.getId())).isNotZero();
	}

	@Test
	@DisplayName("deleting the mask layer frees every layer above it again")
	void deletingTheMaskFreesLayersAboveIt() throws Exception {
		mark(maske);
		assertThat(clipVersionOf(oben.getId())).isNotZero();

		mockMvc.perform(delete("/api/layers/{layerId}", maske.getId()))
				.andExpect(status().isNoContent());

		assertThat(clipVersionOf(oben.getId())).isZero();

		String json = mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		for (tools.jackson.databind.JsonNode layer : new tools.jackson.databind.ObjectMapper().readTree(json)) {
			assertThat(layer.get("clipMask").asBoolean())
					.as("kein Layer sollte nach dem Löschen der Maske noch als Maske markiert sein")
					.isFalse();
		}
	}

	// --- helpers -----------------------------------------------------------------

	private void mark(Layer layer) throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMask\": true }"))
				.andExpect(status().isOk());
	}

	private long clipVersionOf(UUID layerId) throws Exception {
		String json = mockMvc.perform(get("/api/layers/{layerId}", layerId))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return new tools.jackson.databind.ObjectMapper().readTree(json).get("clipVersion").asLong();
	}

	/**
	 * Simulates what an edit endpoint outside this package would do: bump the mask's
	 * dataVersion in a transaction of its own, committed before the assertion reads it
	 * back through the HTTP layer. CONTRACT.md phase 19 only asks that clipVersion react
	 * to the mask's dataVersion changing, not that this test package own the edit path.
	 */
	private void bumpDataVersionInItsOwnTransaction(UUID layerId) {
		new TransactionTemplate(transactionManager).executeWithoutResult(
				status -> layerRepository.bumpDataVersion(layerId));
	}

	private String orderBody(UUID... ids) {
		String quoted = java.util.Arrays.stream(ids)
				.map(id -> "\"" + id + "\"")
				.collect(Collectors.joining(", "));
		return "{ \"layerIdsBottomToTop\": [" + quoted + "] }";
	}
}
