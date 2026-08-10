package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.tiles.TileTestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for the layer catalog API: list, detail, patch and delete.
 *
 * The physical payload table is a minimal stand-in -- one row is enough to prove
 * DELETE actually drops it, which is the whole point of {@link LayerService#delete}
 * existing instead of a plain {@code repository.delete}.
 */
@Import(TileTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LayerControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository layerFieldRepository;

	private Project project;
	private Layer layer;
	private String tableName;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Layer-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql(("INSERT INTO " + table
				+ " (geom) VALUES (ST_Multi(ST_MakeEnvelope(0, 0, 10, 10, 25832)))")).update();

		Layer newLayer = new Layer(layerId, project, "Gebäude", tableName, "MULTIPOLYGON", 25832);
		newLayer.setFeatureCount(1);
		layer = layerRepository.saveAndFlush(newLayer);

		layerFieldRepository.saveAndFlush(
				new LayerField(layer, "Gebäudehöhe", "gebaeudehoehe", "double precision", 0));
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	@Test
	void listsLayersForAProject() throws Exception {
		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(layer.getId().toString()))
				.andExpect(jsonPath("$[0].name").value("Gebäude"))
				.andExpect(jsonPath("$[0].geometryType").value("MULTIPOLYGON"))
				.andExpect(jsonPath("$[0].srid").value(25832))
				.andExpect(jsonPath("$[0].featureCount").value(1));
	}

	@Test
	void returnsNotFoundForAnUnknownProject() throws Exception {
		mockMvc.perform(get("/api/projects/{projectId}/layers", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsLayerDetailWithFields() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(layer.getId().toString()))
				.andExpect(jsonPath("$.fields", hasSize(1)))
				.andExpect(jsonPath("$.fields[0].sourceName").value("Gebäudehöhe"))
				.andExpect(jsonPath("$.fields[0].columnName").value("gebaeudehoehe"))
				.andExpect(jsonPath("$.style").doesNotExist());
	}

	@Test
	void returnsNotFoundForAnUnknownLayer() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	@Test
	void updatesVisibilityAndZoomRange() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "name": "Gebäude (aktualisiert)", "visible": false, "zIndex": 3, "minZoom": 5, "maxZoom": 18 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Gebäude (aktualisiert)"))
				.andExpect(jsonPath("$.visible").value(false))
				.andExpect(jsonPath("$.zIndex").value(3))
				.andExpect(jsonPath("$.minZoom").value(5))
				.andExpect(jsonPath("$.maxZoom").value(18));

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getName()).isEqualTo("Gebäude (aktualisiert)");
		assertThat(reloaded.isVisible()).isFalse();
		assertThat(reloaded.getMinZoom()).isEqualTo(5);
		assertThat(reloaded.getMaxZoom()).isEqualTo(18);
	}

	@Test
	void rejectsAMinZoomAboveMaxZoom() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"minZoom\": 20, \"maxZoom\": 10 }"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsABlankName() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"   \" }"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void deletesTheLayerAndDropsThePhysicalTable() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isNoContent());

		assertThat(layerRepository.findById(layer.getId())).isEmpty();

		Boolean tableStillExists = jdbc.sql("SELECT to_regclass('gis_data.' || :tableName) IS NOT NULL")
				.param("tableName", tableName)
				.query(Boolean.class)
				.single();
		assertThat(tableStillExists).isFalse();
	}

	@Test
	void returnsNotFoundWhenDeletingAnUnknownLayer() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}
}
