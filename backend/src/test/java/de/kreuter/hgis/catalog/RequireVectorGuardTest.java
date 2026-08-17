package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
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

/**
 * Every endpoint that assumes a layer has a payload table has to refuse a map image
 * (kind {@code WMS}) with 409, not a 500 -- {@link Layer#requireVector()}'s whole reason
 * to exist (plan "Kartenbilder aus dem Geoportal Hamburg", stage 1). One fixture, one
 * test per guarded endpoint, so a future endpoint that forgets the guard shows up here
 * as a wrong status code rather than a stack trace in production.
 *
 * <p>The tail of the class proves the opposite: read, rename, reorder and delete must
 * keep working exactly as before for a map image, since the guard is scoped to the
 * handful of methods that actually touch a payload table, not to the layer as a whole.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequireVectorGuardTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	private Project project;
	private Layer wmsLayer;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Kartenbild-Schutztest " + UUID.randomUUID(), null, 25832, "osm"));
		wmsLayer = layerRepository.saveAndFlush(new Layer(UUID.randomUUID(), project, "Kartenbild",
				"https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan", List.of("stadtplan"), "image/png", null,
				true));
	}

	@AfterEach
	void tearDown() {
		layerRepository.findById(wmsLayer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	private void expectConflict(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		mockMvc.perform(request)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Layer 'Kartenbild' ist ein Kartenbild und hat keine eigenen Objektdaten."));
	}

	@Test
	@DisplayName("a vector tile request is a 409, not a 500")
	void tileRequestIsConflict() throws Exception {
		expectConflict(get("/api/layers/{id}/tiles/0/0/0.mvt", wmsLayer.getId()));
	}

	@Test
	void featureListIsConflict() throws Exception {
		expectConflict(get("/api/layers/{id}/features", wmsLayer.getId()));
	}

	@Test
	void featureGetIsConflict() throws Exception {
		expectConflict(get("/api/layers/{id}/features/{fid}", wmsLayer.getId(), 1));
	}

	@Test
	void featureFidsIsConflict() throws Exception {
		expectConflict(get("/api/layers/{id}/features/fids", wmsLayer.getId()));
	}

	@Test
	@DisplayName("an edit batch is a 409 even when it would otherwise be rejected as empty (the guard runs first)")
	void editIsConflict() throws Exception {
		expectConflict(post("/api/layers/{id}/edits", wmsLayer.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"));
	}

	@Test
	void splitIsConflict() throws Exception {
		expectConflict(post("/api/layers/{id}/features/{fid}/split", wmsLayer.getId(), 1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"line":{"type":"LineString","coordinates":[[0,0],[1,1]]}}
						"""));
	}

	@Test
	void mergeIsConflict() throws Exception {
		expectConflict(post("/api/layers/{id}/features/merge", wmsLayer.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fids":[1,2],"leadFid":1}
						"""));
	}

	@Test
	void addFieldIsConflict() throws Exception {
		expectConflict(post("/api/layers/{id}/fields", wmsLayer.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Test","type":"TEXT"}
						"""));
	}

	@Test
	void classifyIsConflict() throws Exception {
		expectConflict(get("/api/layers/{id}/classify", wmsLayer.getId()).param("field", "irgendwas"));
	}

	@Test
	void valuesIsConflict() throws Exception {
		expectConflict(get("/api/layers/{id}/values", wmsLayer.getId()).param("field", "irgendwas"));
	}

	@Test
	void exportIsConflict() throws Exception {
		expectConflict(get("/api/layers/{id}/export.geojson", wmsLayer.getId()));
	}

	// --- a real 404 stays a 404, not a 409, for the same endpoints ----------------------

	@Test
	@DisplayName("an unknown layer id is still 404, not swallowed by the vector guard")
	void anUnknownLayerIsStillNotFound() throws Exception {
		mockMvc.perform(get("/api/layers/{id}/features", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	// --- what must keep working exactly because the guard is scoped narrowly -----------

	@Test
	@DisplayName("PATCH without style still works on a map image -- only applyStyle is guarded")
	void renamingAMapImageStillWorks() throws Exception {
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Umbenannt"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Umbenannt"));
	}

	@Test
	@DisplayName("reordering a project that includes a map image works unchanged")
	void reorderingWithAMapImageIncludedWorks() throws Exception {
		mockMvc.perform(put("/api/projects/{projectId}/layers/order", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"layerIdsBottomToTop\": [\"" + wmsLayer.getId() + "\"]}"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("a map image can still be deleted -- moved to the trash like a vector layer")
	void deletingAMapImageStillWorks() throws Exception {
		mockMvc.perform(delete("/api/layers/{id}", wmsLayer.getId()))
				.andExpect(status().isOk());

		assertThat(layerRepository.findById(wmsLayer.getId())).get()
				.extracting(Layer::isTrashed).isEqualTo(true);
	}

	@Test
	@DisplayName("purging a map image works too -- no table to drop, but the catalog row goes")
	void purgingAMapImageStillWorks() throws Exception {
		mockMvc.perform(delete("/api/layers/{id}", wmsLayer.getId()))
				.andExpect(status().isOk());
		mockMvc.perform(delete("/api/layers/{id}/purge", wmsLayer.getId()))
				.andExpect(status().isOk());

		assertThat(layerRepository.findById(wmsLayer.getId())).isEmpty();
	}

	@Test
	@DisplayName("the list and detail endpoints read a map image fine and report kind and wms")
	void listAndDetailReadAMapImageFine() throws Exception {
		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].kind").value("WMS"))
				.andExpect(jsonPath("$[0].geometryType").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$[0].srid").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$[0].wms.serviceUrl").value("https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan"))
				.andExpect(jsonPath("$[0].wms.layers[0]").value("stadtplan"))
				.andExpect(jsonPath("$[0].wms.imageFormat").value("image/png"))
				.andExpect(jsonPath("$[0].wms.queryable").value(true));

		mockMvc.perform(get("/api/layers/{id}", wmsLayer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("WMS"))
				.andExpect(jsonPath("$.fields").isEmpty())
				.andExpect(jsonPath("$.wms.serviceUrl").value("https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan"));
	}
}
