package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A project's opacity for its basemap (CONTRACT.md phase 18) -- unlike a layer's, it is
 * never null: a project always has a basemap, so it always has an opacity for it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectBasemapTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projectRepository;

	private Project project;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Karten-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void tearDown() {
		projectRepository.deleteById(project.getId());
	}

	@Test
	void defaultsToFullOpacity() throws Exception {
		mockMvc.perform(get("/api/projects/{id}", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemapOpacity").value(1.0));
	}

	@Test
	void setsAndReadsTheOpacity() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": 0.75 }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemapOpacity").value(0.75));

		Project reloaded = projectRepository.findById(project.getId()).orElseThrow();
		assertThat(reloaded.getBasemapOpacity()).isEqualTo(0.75);

		mockMvc.perform(get("/api/projects/{id}", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemapOpacity").value(0.75));
	}

	@Test
	void anAbsentFieldLeavesThePreviousOpacityUnchanged() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": 0.75 }"))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Umbenannt " + UUID.randomUUID() + "\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemapOpacity").value(0.75));
	}

	@Test
	void rejectsAnOpacityOutsideZeroToOne() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": 1.5 }"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": -0.1 }"))
				.andExpect(status().isBadRequest());
	}
}
