package de.kreuter.hgis.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectDuplicateControllerTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private ProjectRepository projects;
	@Autowired private ProjectDeletionService deletion;

	private Project source;

	@AfterEach
	void cleanUp() {
		if (source != null) projects.findById(source.getId()).ifPresent(project -> deletion.deleteProject(project.getId()));
	}

	@Test
	void acceptsDuplicateJobAndRejectsInvalidRequests() throws Exception {
		source = projects.saveAndFlush(new Project("Controller Kopie " + UUID.randomUUID(), null, 25832, "osm"));

		mockMvc.perform(post("/api/projects/{id}/duplicate", source.getId())
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.type").value("DUPLICATE"));
		mockMvc.perform(post("/api/projects/{id}/duplicate", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/projects/{id}/duplicate", source.getId())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/projects/{id}/duplicate", source.getId())
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + "x".repeat(201) + "\"}"))
				.andExpect(status().isBadRequest());
	}
}
