package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.common.ClientId;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@Import(TestcontainersConfiguration.class)
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
	@DisplayName("delete moves the layer to the trash and answers with the trash entry -- "
			+ "catalog row and physical table both survive")
	void deleteMovesTheLayerToTheTrashInsteadOfDroppingIt() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId())
						.header(ClientId.HEADER, "test-client"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(layer.getId().toString()))
				.andExpect(jsonPath("$.name").value("Gebäude"))
				.andExpect(jsonPath("$.featureCount").value(1))
				.andExpect(jsonPath("$.deletedBy").value("test-client"))
				.andExpect(jsonPath("$.deletedAt").exists());

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.isTrashed()).isTrue();
		assertThat(reloaded.getDeletedAt()).isNotNull();
		assertThat(reloaded.getDeletedBy()).isEqualTo("test-client");

		Boolean tableStillExists = jdbc.sql("SELECT to_regclass('gis_data.' || :tableName) IS NOT NULL")
				.param("tableName", tableName)
				.query(Boolean.class)
				.single();
		assertThat(tableStillExists).isTrue();
	}

	@Test
	void returnsNotFoundWhenDeletingAnUnknownLayer() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a trashed layer disappears from the ordinary layer list")
	void trashedLayerIsHiddenFromTheOrdinaryList() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	@DisplayName("deleting an already-trashed layer is a conflict, not a silent no-op")
	void deletingAnAlreadyTrashedLayerConflicts() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk());

		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("the trash lists name, deletion time, object count and who deleted it")
	void trashListsWhatWasDeleted() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId())
						.header(ClientId.HEADER, "cli-abc"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/projects/{projectId}/trash", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(layer.getId().toString()))
				.andExpect(jsonPath("$[0].name").value("Gebäude"))
				.andExpect(jsonPath("$[0].featureCount").value(1))
				.andExpect(jsonPath("$[0].deletedBy").value("cli-abc"))
				.andExpect(jsonPath("$[0].deletedAt").exists());
	}

	@Test
	@DisplayName("restore brings a trashed layer back and it reappears in the list")
	void restoreBringsALayerBackFromTheTrash() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/layers/{layerId}/restore", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(layer.getId().toString()));

		assertThat(layerRepository.findById(layer.getId()).orElseThrow().isTrashed()).isFalse();

		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	@DisplayName("restoring a layer that is not in the trash is a conflict")
	void restoringANonTrashedLayerConflicts() throws Exception {
		mockMvc.perform(post("/api/layers/{layerId}/restore", layer.getId()))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("purge is the only path left that actually drops the payload table, and answers "
			+ "with the trash entry as it stood right before the purge -- proving deletedAt/deletedBy "
			+ "describe the trashing (test-client's delete), not the purge call itself (no header)")
	void purgeDropsThePhysicalTableAndRemovesTheCatalogRow() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}", layer.getId())
						.header(ClientId.HEADER, "cli-purge"))
				.andExpect(status().isOk());

		mockMvc.perform(delete("/api/layers/{layerId}/purge", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(layer.getId().toString()))
				.andExpect(jsonPath("$.name").value("Gebäude"))
				.andExpect(jsonPath("$.featureCount").value(1))
				.andExpect(jsonPath("$.deletedBy").value("cli-purge"))
				.andExpect(jsonPath("$.deletedAt").exists());

		assertThat(layerRepository.findById(layer.getId())).isEmpty();

		Boolean tableStillExists = jdbc.sql("SELECT to_regclass('gis_data.' || :tableName) IS NOT NULL")
				.param("tableName", tableName)
				.query(Boolean.class)
				.single();
		assertThat(tableStillExists).isFalse();
		// tearDown's own cleanup still runs against layer.getId() and project.getId(),
		// both plain fields on the in-memory objects; it simply finds nothing left to
		// delete for the layer, since purge already did that.
	}

	/**
	 * The shared fixture's own layer always has {@code featureCount} explicitly set to 1
	 * (see {@link #setUp}), so {@link #purgeDropsThePhysicalTableAndRemovesTheCatalogRow}
	 * never exercises the value {@code featureCount} actually defaults to on {@link Layer}:
	 * a layer nobody ever populated -- created, then trashed and purged empty -- carries
	 * whatever the primitive {@code long} field starts at. This uses its own project and
	 * layer, not the shared fixture, specifically so it stays 0 the whole way through.
	 */
	@Test
	@DisplayName("purging an empty vector layer (featureCount never set) reports featureCount 0, "
			+ "and the table is actually gone")
	void purgingAnEmptyVectorLayerReportsZeroFeatureCount() throws Exception {
		Project emptyProject = projectRepository.saveAndFlush(
				new Project("Leerer-Layer-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));
		UUID emptyLayerId = UUID.randomUUID();
		String emptyTableName = SqlIdentifier.tableName(emptyLayerId);
		try {
			jdbc.sql("""
					CREATE TABLE %s (
					    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
					    geom geometry(MultiPolygon, 25832) NOT NULL
					)
					""".formatted(SqlIdentifier.quoteLayerTable(emptyTableName))).update();
			Layer emptyLayer = layerRepository.saveAndFlush(
					new Layer(emptyLayerId, emptyProject, "Leerer Layer", emptyTableName, "MULTIPOLYGON", 25832));

			mockMvc.perform(delete("/api/layers/{layerId}", emptyLayer.getId()))
					.andExpect(status().isOk());

			mockMvc.perform(delete("/api/layers/{layerId}/purge", emptyLayer.getId()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.name").value("Leerer Layer"))
					.andExpect(jsonPath("$.featureCount").value(0))
					.andExpect(jsonPath("$.deletedAt").exists());

			Boolean tableStillExists = jdbc.sql("SELECT to_regclass('gis_data.' || :tableName) IS NOT NULL")
					.param("tableName", emptyTableName)
					.query(Boolean.class)
					.single();
			assertThat(tableStillExists)
					.as("the table must actually be gone, not just the catalog row")
					.isFalse();
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(emptyTableName)).update();
			layerRepository.findById(emptyLayerId).ifPresent(layerRepository::delete);
			projectRepository.deleteById(emptyProject.getId());
		}
	}

	@Test
	@DisplayName("purge without going through the trash first is a conflict, not a shortcut")
	void purgingANonTrashedLayerConflicts() throws Exception {
		mockMvc.perform(delete("/api/layers/{layerId}/purge", layer.getId()))
				.andExpect(status().isConflict());
	}
}
