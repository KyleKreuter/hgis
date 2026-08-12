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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A layer can be marked as one of the project's clip masks, in any of four modes
 * (CONTRACT.md phase 21): any number per project, restricted to polygon geometry,
 * reported through the same PATCH endpoint that already carries the rest of a layer's
 * settings. This class covers the catalog side -- marking, unmarking, an unknown mode,
 * several masks coexisting, and clipVersion reacting to a mask edit, a reorder or a
 * mode switch. Whether {@code MvtService} actually cuts the geometry, in each mode and
 * for several masks at once, is covered separately, by {@code tiles.MvtServiceClipTest},
 * {@code tiles.MvtServiceOutsideClipTest} and {@code tiles.TileControllerClipTest}.
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

	/** Bottom to top: unten, maskeA, maskeB, oben, punktlayer -- two masks both below "oben". */
	private Layer unten;
	private Layer maskeA;
	private Layer maskeB;
	private Layer oben;
	private Layer punktlayer;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Zuschnitt-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		unten = saveLayer("Unten", "MULTIPOLYGON", 0);
		maskeA = saveLayer("Maske A", "MULTIPOLYGON", 1);
		maskeB = saveLayer("Maske B", "MULTIPOLYGON", 2);
		oben = saveLayer("Oben", "MULTIPOLYGON", 3);
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
		mockMvc.perform(patch("/api/layers/{layerId}", maskeA.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMode\": \"insideClipped\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clipMode").value("insideClipped"));

		mockMvc.perform(get("/api/layers/{layerId}", maskeA.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clipMode").value("insideClipped"));

		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id=='" + maskeA.getId() + "')].clipMode").value("insideClipped"))
				// A JSONPath filter always evaluates to a list, even for one match, and
				// Spring does not unwrap a singleton list for a Hamcrest matcher the way
				// it does for a plain expected value -- hence contains(), not nullValue()
				// alone.
				.andExpect(jsonPath("$[?(@.id=='" + oben.getId() + "')].clipMode")
						.value(Matchers.contains(Matchers.nullValue())));
	}

	@Test
	@DisplayName("each of the four known clip modes round-trips through the PATCH endpoint")
	void marksAPolygonLayerWithEachKnownMode() throws Exception {
		for (String mode : new String[] { "insideWhole", "insideClipped", "outsideWhole", "outsideClipped" }) {
			mockMvc.perform(patch("/api/layers/{layerId}", maskeA.getId())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{ \"clipMode\": \"" + mode + "\" }"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clipMode").value(mode));
		}
	}

	@Test
	@DisplayName("an explicit null clears the mode again")
	void unmarksTheMask() throws Exception {
		mark(maskeA, "insideClipped");

		mockMvc.perform(patch("/api/layers/{layerId}", maskeA.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMode\": null }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clipMode").value(Matchers.nullValue()));

		assertThat(layerRepository.findById(maskeA.getId()).orElseThrow().isMask()).isFalse();
	}

	@Test
	@DisplayName("a point layer is rejected as a mask with 400")
	void rejectsAPointLayerAsAMask() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", punktlayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMode\": \"insideClipped\" }"))
				.andExpect(status().isBadRequest());

		assertThat(layerRepository.findById(punktlayer.getId()).orElseThrow().isMask()).isFalse();
	}

	@Test
	@DisplayName("an unknown clip mode is rejected with 400")
	void rejectsAnUnknownClipMode() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", maskeA.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMode\": \"invertiert\" }"))
				.andExpect(status().isBadRequest());

		assertThat(layerRepository.findById(maskeA.getId()).orElseThrow().isMask()).isFalse();
	}

	/**
	 * CONTRACT.md phase 21: the two tokens phase 19/20 used, {@code "inside"} and
	 * {@code "outside"}, are gone without a replacement of the same name -- migration
	 * V6 rewrites any stored row, but the API must no longer accept the old spelling.
	 */
	@Test
	@DisplayName("the retired inside/outside tokens are rejected with 400")
	void rejectsTheRetiredTokens() throws Exception {
		for (String retired : new String[] { "inside", "outside" }) {
			mockMvc.perform(patch("/api/layers/{layerId}", maskeA.getId())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{ \"clipMode\": \"" + retired + "\" }"))
					.andExpect(status().isBadRequest());
		}
	}

	/**
	 * CONTRACT.md phase 21: the rule that a second mask silently demotes the first is
	 * gone. Any number of layers may be masks in the same project at once.
	 */
	@Test
	@DisplayName("marking a second layer as a mask leaves the first one marked too")
	void twoMasksCanBeMarkedAtOnce() throws Exception {
		mark(maskeA, "insideClipped");

		mockMvc.perform(patch("/api/layers/{layerId}", maskeB.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMode\": \"outsideClipped\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clipMode").value("outsideClipped"));

		assertThat(layerRepository.findById(maskeA.getId()).orElseThrow().isMask()).isTrue();
		assertThat(layerRepository.findById(maskeB.getId()).orElseThrow().isMask()).isTrue();
	}

	@Test
	@DisplayName("a layer above the masks gets a non-zero clipVersion, one below and the masks themselves stay at zero")
	void clipVersionReflectsPositionRelativeToTheMasks() throws Exception {
		mark(maskeA, "insideClipped");
		mark(maskeB, "outsideClipped");

		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id=='" + unten.getId() + "')].clipVersion").value(0))
				.andExpect(jsonPath("$[?(@.id=='" + maskeA.getId() + "')].clipVersion").value(0));

		long clipVersion = clipVersionOf(oben.getId());
		assertThat(clipVersion).isNotZero();
	}

	@Test
	@DisplayName("adding a second mask below a layer changes its clipVersion again")
	void addingASecondMaskChangesClipVersion() throws Exception {
		mark(maskeA, "insideClipped");
		long withOneMask = clipVersionOf(oben.getId());

		mark(maskeB, "outsideClipped");
		long withTwoMasks = clipVersionOf(oben.getId());

		assertThat(withTwoMasks).isNotEqualTo(withOneMask);
	}

	@Test
	@DisplayName("editing a mask's data changes the clipVersion of layers above it")
	void editingAMaskChangesClipVersion() throws Exception {
		mark(maskeA, "insideClipped");
		long before = clipVersionOf(oben.getId());

		bumpDataVersionInItsOwnTransaction(maskeA.getId());

		long after = clipVersionOf(oben.getId());
		assertThat(after).isNotEqualTo(before);
	}

	@Test
	@DisplayName("dragging a layer above a mask changes its clipVersion, with no edit to the layer itself")
	void reorderingAcrossAMaskChangesClipVersion() throws Exception {
		mark(maskeA, "insideClipped");
		assertThat(clipVersionOf(unten.getId())).isZero();

		// New bottom-to-top order: maskeA, unten, maskeB, oben, punktlayer -- unten now
		// sits above maskeA.
		mockMvc.perform(put("/api/projects/{projectId}/layers/order", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(maskeA.getId(), unten.getId(), maskeB.getId(),
								oben.getId(), punktlayer.getId())))
				.andExpect(status().isOk());

		assertThat(clipVersionOf(unten.getId())).isNotZero();
	}

	/**
	 * CONTRACT.md phase 21: clipVersion has to include the mode, or the tile address
	 * would stay the same when a mask switches mode, and the cache would keep serving
	 * the wrongly clipped tile.
	 */
	@Test
	@DisplayName("switching a mask's mode changes the clipVersion of a layer above it")
	void switchingModeChangesClipVersion() throws Exception {
		mark(maskeA, "insideClipped");
		long insideVersion = clipVersionOf(oben.getId());

		mockMvc.perform(patch("/api/layers/{layerId}", maskeA.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMode\": \"outsideClipped\" }"))
				.andExpect(status().isOk());

		assertThat(clipVersionOf(oben.getId())).isNotEqualTo(insideVersion);
	}

	@Test
	@DisplayName("deleting one mask layer leaves the other's effect on layers above it intact")
	void deletingOneMaskLeavesTheOthersEffect() throws Exception {
		mark(maskeA, "insideClipped");
		mark(maskeB, "outsideClipped");
		assertThat(clipVersionOf(oben.getId())).isNotZero();

		mockMvc.perform(delete("/api/layers/{layerId}", maskeA.getId()))
				.andExpect(status().isNoContent());

		assertThat(clipVersionOf(oben.getId())).isNotZero();
		assertThat(layerRepository.findById(maskeB.getId()).orElseThrow().isMask()).isTrue();
	}

	@Test
	@DisplayName("deleting the last mask layer frees every layer above it again")
	void deletingTheLastMaskFreesLayersAboveIt() throws Exception {
		mark(maskeA, "insideClipped");
		assertThat(clipVersionOf(oben.getId())).isNotZero();

		mockMvc.perform(delete("/api/layers/{layerId}", maskeA.getId()))
				.andExpect(status().isNoContent());

		assertThat(clipVersionOf(oben.getId())).isZero();

		String json = mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		for (tools.jackson.databind.JsonNode layer : new tools.jackson.databind.ObjectMapper().readTree(json)) {
			assertThat(layer.get("clipMode").isNull())
					.as("kein Layer sollte nach dem Löschen der Maske noch als Maske markiert sein")
					.isTrue();
		}
	}

	// --- helpers -----------------------------------------------------------------

	private void mark(Layer layer, String mode) throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMode\": \"" + mode + "\" }"))
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
