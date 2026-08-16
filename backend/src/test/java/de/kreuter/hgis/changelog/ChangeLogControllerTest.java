package de.kreuter.hgis.changelog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.ClientId;
import de.kreuter.hgis.common.SqlIdentifier;
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

/**
 * HTTP-level coverage for {@code GET /api/projects/{projectId}/changes} (CONTRACT.md
 * "Schreibstufe" 1.2). {@link ChangeLogFlowTest} already covers the service in depth;
 * this only pins down the wire format and the parameter handling.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChangeLogControllerTest {

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
	void setUp() throws Exception {
		project = projectRepository.saveAndFlush(
				new Project("Protokoll-HTTP-Test " + UUID.randomUUID(), null, 25832, "osm"));

		mockMvc.perform(post("/api/projects/{projectId}/layers", project.getId())
						.header(ClientId.HEADER, "http-test-client")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "name": "Protokolliert", "geometryType": "MULTIPOLYGON" }
								"""))
				.andExpect(status().isCreated());

		layer = layerRepository.findByProjectOrdered(project.getId()).get(0);
		tableName = layer.getTableName();
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		projectRepository.deleteById(project.getId());
	}

	@Test
	@DisplayName("creating a layer over HTTP shows up as a layer.create entry, newest first")
	void listsTheEntryForALayerCreatedOverHttp() throws Exception {
		mockMvc.perform(get("/api/projects/{projectId}/changes", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
				.andExpect(jsonPath("$[0].action").value("layer.create"))
				.andExpect(jsonPath("$[0].layerId").value(layer.getId().toString()))
				.andExpect(jsonPath("$[0].layerName").value("Protokolliert"))
				.andExpect(jsonPath("$[0].clientName").value("http-test-client"))
				.andExpect(jsonPath("$[0].affectedCount").value(1))
				.andExpect(jsonPath("$[0].deletedRows").doesNotExist());
	}

	@Test
	@DisplayName("an unknown project is a 404, not an empty list")
	void returnsNotFoundForAnUnknownProject() throws Exception {
		mockMvc.perform(get("/api/projects/{projectId}/changes", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("size outside 1..1000 is rejected with 400")
	void rejectsAnOutOfRangeSize() throws Exception {
		mockMvc.perform(get("/api/projects/{projectId}/changes", project.getId())
						.param("size", "0"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/projects/{projectId}/changes", project.getId())
						.param("size", "1001"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("deleting the layer produces a layer.delete entry alongside the create")
	void deletingTheLayerAddsAnEntry() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/projects/{projectId}/changes", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
				.andExpect(jsonPath("$[0].action").value("layer.delete"))
				.andExpect(jsonPath("$[1].action").value("layer.create"));
	}
}
