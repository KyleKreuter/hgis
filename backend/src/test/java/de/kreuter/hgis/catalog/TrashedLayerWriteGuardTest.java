package de.kreuter.hgis.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
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
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * A layer in the trash must refuse every write, not only disappear from the list
 * (CONTRACT.md "Schreibstufe" 1.1, orchestrator follow-up). Without this, the Python
 * library -- which addresses a layer by id and never has to see it in a list -- could
 * keep writing to a layer the user believes is gone; restoring it later would then bring
 * back something other than what looked deleted.
 *
 * <p>One fixture, one test per guarded write, mirroring {@link RequireVectorGuardTest}'s
 * shape for the same kind of cross-cutting guard. The tail proves the opposite: ordinary
 * reads keep working on a trashed layer, and the guard itself runs before any other
 * validation -- a malformed body on a trashed layer is still 409, not 400.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TrashedLayerWriteGuardTest {

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
	private long fid;

	@BeforeEach
	void setUp() throws Exception {
		project = projectRepository.saveAndFlush(
				new Project("Papierkorb-Schreibschutz " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();
		fid = jdbc.sql("INSERT INTO " + table
						+ " (geom) VALUES (ST_Multi(ST_MakeEnvelope(0, 0, 10, 10, 25832))) RETURNING fid")
				.query(Long.class)
				.single();

		Layer created = new Layer(layerId, project, "Geschützt", tableName, "MULTIPOLYGON", 25832);
		created.setFeatureCount(1);
		layer = layerRepository.saveAndFlush(created);

		mockMvc.perform(delete("/api/layers/{id}", layer.getId()))
				.andExpect(status().isNoContent());
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	private void expectConflict(RequestBuilder request) throws Exception {
		mockMvc.perform(request)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail")
						.value("Layer 'Geschützt' liegt im Papierkorb und kann nicht mehr geändert werden."));
	}

	@Test
	@DisplayName("PATCH on a trashed layer is a 409")
	void patchIsConflict() throws Exception {
		expectConflict(patch("/api/layers/{id}", layer.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"visible":false}
						"""));
	}

	@Test
	@DisplayName("an edit batch on a trashed layer is a 409 even when the body would otherwise be rejected as empty")
	void editIsConflict() throws Exception {
		expectConflict(post("/api/layers/{id}/edits", layer.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"));
	}

	@Test
	void splitIsConflict() throws Exception {
		expectConflict(post("/api/layers/{id}/features/{fid}/split", layer.getId(), fid)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"line":{"type":"LineString","coordinates":[[0,0],[10,10]]}}
						"""));
	}

	@Test
	void mergeIsConflict() throws Exception {
		expectConflict(post("/api/layers/{id}/features/merge", layer.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fids":[1,2],"leadFid":1}
						"""));
	}

	@Test
	@DisplayName("adding a field to a trashed layer is a 409")
	void addFieldIsConflict() throws Exception {
		expectConflict(post("/api/layers/{id}/fields", layer.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Test","type":"TEXT"}
						"""));
	}

	@Test
	@DisplayName("deleting a field from a trashed layer is a 409 -- the guard runs before the field is even looked up")
	void deleteFieldIsConflict() throws Exception {
		expectConflict(delete("/api/layers/{id}/fields/{fieldId}", layer.getId(), UUID.randomUUID()));
	}

	// --- a trashed layer still reads fine -----------------------------------------------

	@Test
	@DisplayName("GET on a trashed layer's detail still works -- only writes are blocked")
	void detailStillReads() throws Exception {
		mockMvc.perform(get("/api/layers/{id}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Geschützt"));
	}

	@Test
	void featureListStillReads() throws Exception {
		mockMvc.perform(get("/api/layers/{id}/features", layer.getId()))
				.andExpect(status().isOk());
	}
}
