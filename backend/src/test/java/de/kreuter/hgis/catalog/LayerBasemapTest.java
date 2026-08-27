package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A layer's own basemap and basemap opacity (CONTRACT.md phase 18): {@code null} means
 * "follow the project's basemap", a state distinct from any concrete value, so setting
 * and resetting both have to be observable through the same PATCH endpoint that already
 * carries the rest of a layer's settings.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LayerBasemapTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	private Project project;
	private Layer layer;
	private String tableName;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Karten-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();

		Layer newLayer = new Layer(layerId, project, "Gebäude", tableName, "MULTIPOLYGON", 25832);
		layer = layerRepository.saveAndFlush(newLayer);
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	@Test
	void anExistingLayerWithoutTheFieldsIsReadAsFollowingTheProject() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").doesNotExist())
				.andExpect(jsonPath("$.basemapOpacity").doesNotExist());

		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].basemap").doesNotExist())
				.andExpect(jsonPath("$[0].basemapOpacity").doesNotExist());
	}

	@Test
	void setsAndReadsBothFields() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"opentopo\", \"basemapOpacity\": 0.6 }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value("opentopo"))
				.andExpect(jsonPath("$.basemapOpacity").value(0.6));

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isEqualTo("opentopo");
		assertThat(reloaded.getBasemapOpacity()).isEqualTo(0.6);

		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value("opentopo"))
				.andExpect(jsonPath("$.basemapOpacity").value(0.6));
	}

	@Test
	void nullResetsBothFieldsToFollowTheProjectAndTheResponseShowsIt() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"opentopo\", \"basemapOpacity\": 0.6 }"))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": null, \"basemapOpacity\": null }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").doesNotExist())
				.andExpect(jsonPath("$.basemapOpacity").doesNotExist());

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isNull();
		assertThat(reloaded.getBasemapOpacity()).isNull();
	}

	@Test
	void anAbsentFieldLeavesAPreviouslySetBasemapUnchanged() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"opentopo\", \"basemapOpacity\": 0.6 }"))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"visible\": false }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value("opentopo"))
				.andExpect(jsonPath("$.basemapOpacity").value(0.6));
	}

	@Test
	void rejectsAnOpacityOutsideZeroToOne() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": 1.5 }"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": -0.1 }"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * Befund 1 (Validierung, 27.08.): a token that is not one of {@code Basemap}'s five
	 * values used to be accepted with 200 and stored forever, silently falling back to
	 * OSM on every client that ever read it -- see {@code doesNotValidateTheBasemapAgainstAnyCatalogue},
	 * this test's predecessor, which asserted exactly that as the intended behaviour.
	 */
	@Test
	void rejectsAnUnknownBasemapToken() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"ein-unbekannter-dienst\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap").value(
						"Unbekannte Hintergrundkarte: ein-unbekannter-dienst. Gültig sind "
								+ "osm, osm-light, osm-dark, opentopo, none."));

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isNull();
	}

	@Test
	void acceptsEveryKnownBasemapToken() throws Exception {
		for (String token : new String[] { "osm", "osm-light", "osm-dark", "opentopo", "none" }) {
			mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{ \"basemap\": \"" + token + "\" }"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.basemap").value(token));
		}
	}
}
