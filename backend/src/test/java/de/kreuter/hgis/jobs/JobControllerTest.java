package de.kreuter.hgis.jobs;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.ingest.PostgisTestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/jobs/{jobId} per the API contract, section 3. */
@Import(PostgisTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JobControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JobService jobService;

	@Autowired
	private ProjectRepository projectRepository;

	@Test
	void returnsAnExistingJob() throws Exception {
		Project project = projectRepository.saveAndFlush(new Project("Job-Controller-Test", null, 25832, "osm"));
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "gebaeude.zip");

		mockMvc.perform(get("/api/jobs/{jobId}", job.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status", is("PENDING")))
				.andExpect(jsonPath("$.type", is("IMPORT")))
				.andExpect(jsonPath("$.filename", is("gebaeude.zip")));
	}

	@Test
	void returns404ForAnUnknownJob() throws Exception {
		mockMvc.perform(get("/api/jobs/{jobId}", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}
}
