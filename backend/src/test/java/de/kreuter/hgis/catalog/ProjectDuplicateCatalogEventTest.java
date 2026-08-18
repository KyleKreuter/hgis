package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.events.dto.EventDtos;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The second transaction-boundary trap the plan "Der Live-Kanal meldet auch
 * Datenaenderungen" was corrected to name: {@link ProjectDuplicateTransactions#copyLayer}
 * runs once per layer, each its own transaction -- so a project with several layers must
 * still reach the live channel once for the whole duplicate, not once per layer copied.
 *
 * @see de.kreuter.hgis.ingest.ImportCatalogEventTest for the same rule proven on the
 *     import side, where the several transactions are batches of one layer rather than
 *     several layers of one operation
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectDuplicateCatalogEventTest {

	@Autowired
	private ProjectDuplicateService duplicateService;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private ProjectDeletionService deletionService;

	@Autowired
	private JobService jobService;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private Project source;

	@AfterEach
	void cleanUp() {
		if (source != null) {
			deletionService.deleteProject(source.getId());
		}
	}

	@Test
	@DisplayName("duplicating a project with several layers reaches the channel exactly once, for the target project")
	void duplicatingSeveralLayersReachesTheChannelOnce() throws Exception {
		source = projectRepository.saveAndFlush(
				new Project("Duplikat-Kanal-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));
		createMinimalLayer(source, "Erste");
		createMinimalLayer(source, "Zweite");
		createMinimalLayer(source, "Dritte");

		MvcResult stream = openStream();
		try {
			Job job = jobService.create(source.getId(), Job.Type.DUPLICATE, null);
			duplicateService.runDuplicate(job.getId(), source.getId(), null);

			JobDtos.Response result = jobService.get(job.getId());
			assertThat(result.status()).isEqualTo("SUCCEEDED");
			UUID targetProjectId = result.outputProjectId();

			List<JsonNode> events = catalogEventsOf(stream);
			assertThat(events)
					.as("one duplicate of three layers, copied in three separate transactions, is still one event")
					.hasSize(1);
			assertThat(events.get(0).get("projectId").stringValue()).isEqualTo(targetProjectId.toString());

			deletionService.deleteProject(targetProjectId);
		}
		finally {
			stream.getRequest().getAsyncContext().complete();
		}
	}

	private Layer createMinimalLayer(Project project, String name) {
		UUID layerId = UUID.randomUUID();
		String tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				  fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				  geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX " + SqlIdentifier.quoteColumn(tableName + "_geom_idx")
				+ " ON " + table + " USING GIST (geom)").update();
		Layer layer = new Layer(layerId, project, name, tableName, "MULTIPOLYGON", 25832);
		return layerRepository.saveAndFlush(layer);
	}

	private MvcResult openStream() throws Exception {
		return mockMvc.perform(get("/api/events"))
				.andExpect(request().asyncStarted())
				.andReturn();
	}

	private static String textOf(MvcResult stream) throws UnsupportedEncodingException {
		return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private List<JsonNode> catalogEventsOf(MvcResult stream) throws Exception {
		List<JsonNode> events = new ArrayList<>();
		for (String block : textOf(stream).split("\n\n")) {
			boolean isCatalogEvent = false;
			String data = null;
			for (String line : block.split("\n")) {
				if (line.startsWith("event:")) {
					isCatalogEvent = line.substring("event:".length()).trim()
							.equals(EventDtos.EventNames.PROJECT_CATALOG);
				}
				else if (line.startsWith("data:")) {
					data = line.substring("data:".length());
				}
			}
			if (isCatalogEvent && data != null) {
				events.add(objectMapper.readTree(data));
			}
		}
		return events;
	}
}
