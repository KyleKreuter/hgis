package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * A map image's only style member (orchestrator amendment to the plan "Kartenbilder aus
 * dem Geoportal Hamburg": {@code style} is not entirely off limits for a {@code WMS}
 * layer after all -- {@code opacity} survives, since a raster overlay is close to
 * unusable next to the data underneath it without one; every other style member is still
 * refused, since a map image has no symbology to classify or colour by.
 *
 * <p>{@code style_version} is the other half of the amendment: unlike a vector layer's
 * style, which bumps it exactly when the set of attributes a tile carries changes,
 * opacity never touches the tile at all -- it is applied client-side to whatever image
 * the layer's own service returns -- so it must never move here, the same rule a pure
 * colour change already follows for a vector layer (see {@code LayerService#applyStyle}
 * and V1__catalog.sql's own comment on the column).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MapImageOpacityStyleTest {

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
				new Project("Kartenbild-Deckkraft " + UUID.randomUUID(), null, 25832, "osm"));
		wmsLayer = layerRepository.saveAndFlush(new Layer(UUID.randomUUID(), project, "Kartenbild",
				"https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan", List.of("stadtplan"), "image/png", null,
				true));
	}

	@AfterEach
	void tearDown() {
		layerRepository.findById(wmsLayer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	@Test
	@DisplayName("a style with only opacity is accepted and stored")
	void acceptsAStyleWithOnlyOpacity() throws Exception {
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": {"opacity": 0.4}}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style.opacity").value(0.4))
				.andExpect(jsonPath("$.styleVersion").value(1));

		Layer reloaded = layerRepository.findById(wmsLayer.getId()).orElseThrow();
		assertThat(reloaded.getStyle()).contains("0.4");
	}

	@Test
	@DisplayName("changing opacity never bumps styleVersion -- it is a client-side render setting, not a tile attribute")
	void changingOpacityNeverBumpsStyleVersion() throws Exception {
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": {"opacity": 0.2}}
								"""))
				.andExpect(status().isOk());
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": {"opacity": 0.9}}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.styleVersion").value(1))
				.andExpect(jsonPath("$.dataVersion").value(1));
	}

	@Test
	@DisplayName("an explicit null resets a map image to full opacity")
	void nullResetsToFullOpacity() throws Exception {
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": {"opacity": 0.3}}
								"""))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": null}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.style").doesNotExist());
	}

	@Test
	@DisplayName("an empty object is accepted -- no field is present to reject")
	void anEmptyStyleObjectIsAccepted() throws Exception {
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": {}}
								"""))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("a style member other than opacity is a 400, naming the field")
	void aFieldOtherThanOpacityIsBadRequest() throws Exception {
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": {"opacity": 0.5, "renderer": {"type": "single"}}}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("renderer")));

		Layer reloaded = layerRepository.findById(wmsLayer.getId()).orElseThrow();
		assertThat(reloaded.getStyle()).as("the rejected write must not have landed").isNull();
	}

	@Test
	@DisplayName("opacity out of range is a 400")
	void opacityOutOfRangeIsBadRequest() throws Exception {
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": {"opacity": 1.5}}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a non-numeric opacity is a 400")
	void nonNumericOpacityIsBadRequest() throws Exception {
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": {"opacity": "hell"}}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a style that is not a JSON object at all is a 400")
	void aNonObjectStyleIsBadRequest() throws Exception {
		mockMvc.perform(patch("/api/layers/{id}", wmsLayer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"style": "opak"}
								"""))
				.andExpect(status().isBadRequest());
	}
}
