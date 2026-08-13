package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deleting a project (plan section E.4) -- the one operation that destroys data outright.
 *
 * The failure it guards against is specific: {@code ON DELETE CASCADE} inside gis_meta
 * removes the catalog rows and leaves the payload tables behind, where nothing can
 * attribute them to anything any more. They would simply occupy disk forever.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProjectDeletionServiceTest {

	@Autowired
	private ProjectDeletionService deletionService;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository fieldRepository;

	@Autowired
	private JdbcClient jdbc;

	private Project project;
	private final List<String> tableNames = new ArrayList<>();

	@BeforeEach
	void createProjectWithThreeLayers() {
		project = projectRepository.saveAndFlush(
				new Project("Löschtest " + UUID.randomUUID(), null, 25832, "osm"));

		for (String name : List.of("Gebäude", "Straßen", "Flurstücke")) {
			UUID layerId = UUID.randomUUID();
			String tableName = SqlIdentifier.tableName(layerId);
			tableNames.add(tableName);

			jdbc.sql("""
					CREATE TABLE %s (
					    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
					    geom geometry(MultiPolygon, 25832) NOT NULL
					)
					""".formatted(SqlIdentifier.quoteLayerTable(tableName))).update();
			jdbc.sql("INSERT INTO " + SqlIdentifier.quoteLayerTable(tableName)
					+ " (geom) VALUES (ST_Multi(ST_MakeEnvelope(0, 0, 10, 10, 25832)))").update();

			Layer layer = layerRepository.saveAndFlush(
					new Layer(layerId, project, name, tableName, "MULTIPOLYGON", 25832));
			fieldRepository.saveAndFlush(new LayerField(layer, "Höhe", "hoehe", "double precision", 0));
		}
	}

	@AfterEach
	void cleanUp() {
		for (String tableName : tableNames) {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		}
		tableNames.clear();
		projectRepository.findById(project.getId()).ifPresent(projectRepository::delete);
	}

	private boolean tableExists(String tableName) {
		return Boolean.TRUE.equals(jdbc
				.sql("SELECT to_regclass('gis_data.' || quote_ident(:name)) IS NOT NULL")
				.param("name", tableName)
				.query(Boolean.class)
				.single());
	}

	@Test
	@DisplayName("deleting a project leaves neither catalog rows nor payload tables behind")
	void removesEverything() {
		assertThat(tableNames).allMatch(this::tableExists);

		deletionService.deleteProject(project.getId());

		assertThat(projectRepository.findById(project.getId())).isEmpty();
		assertThat(layerRepository.findByProjectOrdered(project.getId())).isEmpty();
		assertThat(tableNames)
				.as("orphaned payload tables would occupy disk with nothing left to name them")
				.noneMatch(this::tableExists);
	}

	@Test
	@DisplayName("layer_field rows go with the layers, via the foreign key cascade")
	void removesFieldsThroughTheCascade() {
		long fieldsBefore = fieldRepository.count();

		deletionService.deleteProject(project.getId());

		assertThat(fieldRepository.count()).isEqualTo(fieldsBefore - 3);
	}

	@Test
	@DisplayName("a failure partway through rolls back the drops that already succeeded")
	void rollsBackEverythingOnFailure() {
		// A view on the last layer makes its DROP fail: PostgreSQL refuses to drop a table
		// other objects depend on. By then the first two tables are already gone, which is
		// exactly the state that has to be undone -- a half-deleted project is worse than
		// either outcome.
		String blocked = tableNames.get(tableNames.size() - 1);
		jdbc.sql("CREATE VIEW gis_data.deletion_guard AS SELECT fid FROM "
				+ SqlIdentifier.quoteLayerTable(blocked)).update();

		try {
			assertThatThrownBy(() -> deletionService.deleteProject(project.getId()))
					.isInstanceOf(RuntimeException.class);

			assertThat(projectRepository.findById(project.getId()))
					.as("the project must still be there")
					.isPresent();
			assertThat(layerRepository.findByProjectOrdered(project.getId()))
					.as("its layers too")
					.hasSize(3);
			assertThat(tableNames)
					.as("DDL is transactional in PostgreSQL, so the earlier drops roll back as well")
					.allMatch(this::tableExists);
		}
		finally {
			jdbc.sql("DROP VIEW IF EXISTS gis_data.deletion_guard").update();
		}
	}

	@Test
	@DisplayName("a catalog row whose table is already gone does not block the deletion")
	void toleratesAMissingPayloadTable() {
		// DROP TABLE IF EXISTS is deliberate: a layer whose table disappeared -- through a
		// crash between the two statements, say -- must still be deletable. Insisting on
		// the table would leave such a project unremovable forever.
		String missing = tableNames.get(0);
		jdbc.sql("DROP TABLE " + SqlIdentifier.quoteLayerTable(missing)).update();

		deletionService.deleteProject(project.getId());

		assertThat(projectRepository.findById(project.getId())).isEmpty();
		assertThat(tableNames).noneMatch(this::tableExists);
	}

	@Test
	void deletingAnEmptyProjectIsFine() {
		Project empty = projectRepository.saveAndFlush(
				new Project("Leer " + UUID.randomUUID(), null, 25832, "osm"));

		deletionService.deleteProject(empty.getId());

		assertThat(projectRepository.findById(empty.getId())).isEmpty();
	}

	@Test
	void deletingAnUnknownProjectDoesNothing() {
		long before = projectRepository.count();

		deletionService.deleteProject(UUID.randomUUID());

		assertThat(projectRepository.count()).isEqualTo(before);
	}

	/**
	 * A map image (kind WMS) has {@code table_name = NULL} (V9__map_image_layer.sql).
	 * Deletion reads {@code table_name} straight off {@code gis_meta.layer} to know what
	 * to drop -- without excluding NULL rows first, {@code tableName.matches(...)} in
	 * {@link ProjectDeletionService#deleteProject} would throw a NullPointerException on
	 * exactly this row, and every vector layer's table would be dropped before that
	 * happened, since the loop processes rows in whatever order the query returned them.
	 */
	@Test
	@DisplayName("a project with a map image layer alongside vector layers still deletes cleanly")
	void deletesAProjectThatAlsoHasAMapImageLayer() {
		Layer mapImage = layerRepository.saveAndFlush(new Layer(UUID.randomUUID(), project, "Kartenbild",
				"https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan", List.of("stadtplan"), "image/png", null,
				true));

		deletionService.deleteProject(project.getId());

		assertThat(projectRepository.findById(project.getId())).isEmpty();
		assertThat(layerRepository.findById(mapImage.getId())).isEmpty();
		assertThat(tableNames)
				.as("the three vector tables must still be dropped despite the map image's NULL table_name")
				.noneMatch(this::tableExists);
	}
}
