package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that V9 lets a layer be a map image without disturbing an existing vector
 * layer, the same way {@link ClipModeMigrationTest} and {@link LayerSourceMigrationTest}
 * prove it for V6 and V7: a database migrated only as far as V8 already holds ordinary
 * layers, and the interesting question is whether they come out the other side
 * unchanged and still {@code kind = 'VECTOR'} -- not whether an empty schema migrates.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MapImageLayerMigrationTest {

	private static final String PROBE_DATABASE = "map_image_layer_migration_probe";

	private final PostgreSQLContainer container;
	private final JdbcClient adminJdbc;

	private DataSource probeDataSource;
	private JdbcClient probeJdbc;

	MapImageLayerMigrationTest(@Autowired PostgreSQLContainer container, @Autowired DataSource dataSource) {
		this.container = container;
		this.adminJdbc = JdbcClient.create(dataSource);
	}

	@BeforeEach
	void createProbeDatabase() {
		adminJdbc.sql("DROP DATABASE IF EXISTS " + PROBE_DATABASE).update();
		adminJdbc.sql("CREATE DATABASE " + PROBE_DATABASE).update();

		probeDataSource = DataSourceBuilder.create()
				.url(probeJdbcUrl())
				.username(container.getUsername())
				.password(container.getPassword())
				.build();
		probeJdbc = JdbcClient.create(probeDataSource);

		probeJdbc.sql("CREATE EXTENSION IF NOT EXISTS postgis").update();
		probeJdbc.sql("CREATE SCHEMA IF NOT EXISTS gis_meta").update();
		probeJdbc.sql("CREATE SCHEMA IF NOT EXISTS gis_data").update();
	}

	@AfterEach
	void dropProbeDatabase() {
		if (probeDataSource instanceof AutoCloseable closeable) {
			try {
				closeable.close();
			}
			catch (Exception ignored) {
				// the container dies with the test run either way
			}
		}
		adminJdbc.sql("DROP DATABASE IF EXISTS " + PROBE_DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void v9LeavesAnExistingVectorLayerAtKindVectorWithEveryColumnIntact() {
		migrateTo("8");
		UUID projectId = insertProject();
		UUID layerId = insertVectorLayer(projectId, "Bestandslayer", 0);

		migrateTo("9");

		Map<String, Object> row = probeJdbc.sql("""
				SELECT kind, table_name, geometry_type, srid,
				       wms_service_url, wms_layers, wms_image_format, wms_legend_url, wms_queryable
				FROM gis_meta.layer WHERE id = :id
				""")
				.param("id", layerId)
				.query()
				.singleRow();

		assertThat(row.get("kind")).isEqualTo("VECTOR");
		assertThat(row.get("table_name")).isEqualTo(SqlIdentifier.tableName(layerId));
		assertThat(row.get("geometry_type")).isEqualTo("MULTIPOLYGON");
		assertThat(row.get("srid")).isEqualTo(25832);
		assertThat(row.get("wms_service_url")).isNull();
		assertThat(row.get("wms_layers")).isNull();
		assertThat(row.get("wms_image_format")).isNull();
		assertThat(row.get("wms_legend_url")).isNull();
		assertThat(row.get("wms_queryable")).isNull();
	}

	@Test
	void v9AcceptsAFreshlyInsertedMapImageLayerWithNoTableColumns() {
		migrateTo("9");
		UUID projectId = insertProject();

		UUID layerId = UUID.randomUUID();
		probeJdbc.sql("""
				INSERT INTO gis_meta.layer (id, project_id, name, kind,
				    wms_service_url, wms_layers, wms_image_format, wms_legend_url, wms_queryable)
				VALUES (:id, :projectId, 'Kartenbild', 'WMS',
				    'https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan', ARRAY['stadtplan'],
				    'image/png', 'https://geodienste.hamburg.de/legend.png', true)
				""")
				.param("id", layerId)
				.param("projectId", projectId)
				.update();

		Map<String, Object> row = probeJdbc.sql("""
				SELECT kind, table_name, geometry_type, srid, wms_service_url, wms_image_format, wms_queryable
				FROM gis_meta.layer WHERE id = :id
				""")
				.param("id", layerId)
				.query()
				.singleRow();

		assertThat(row.get("kind")).isEqualTo("WMS");
		assertThat(row.get("table_name")).isNull();
		assertThat(row.get("geometry_type")).isNull();
		assertThat(row.get("srid")).isNull();
		assertThat(row.get("wms_service_url")).isEqualTo("https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan");
		assertThat(row.get("wms_image_format")).isEqualTo("image/png");
		assertThat(row.get("wms_queryable")).isEqualTo(true);
	}

	@Test
	void v9RejectsAVectorLayerWithAnyTableColumnMissing() {
		migrateTo("9");
		UUID projectId = insertProject();

		assertThat(insertRejected(projectId, """
				INSERT INTO gis_meta.layer (id, project_id, name, kind, table_name, geometry_type, srid)
				VALUES (:id, :projectId, 'Kaputt', 'VECTOR', :tableName, 'MULTIPOLYGON', NULL)
				""")).as("srid missing").isTrue();

		assertThat(insertRejected(projectId, """
				INSERT INTO gis_meta.layer (id, project_id, name, kind, table_name, geometry_type, srid)
				VALUES (:id, :projectId, 'Kaputt', 'VECTOR', :tableName, NULL, 25832)
				""")).as("geometry_type missing").isTrue();

		assertThat(insertRejected(projectId, """
				INSERT INTO gis_meta.layer (id, project_id, name, kind, table_name, geometry_type, srid)
				VALUES (:id, :projectId, 'Kaputt', 'VECTOR', NULL, 'MULTIPOLYGON', 25832)
				""")).as("table_name missing").isTrue();
	}

	@Test
	void v9RejectsAMapImageLayerThatStillCarriesAnyTableColumn() {
		migrateTo("9");
		UUID projectId = insertProject();

		assertThat(insertRejected(projectId, """
				INSERT INTO gis_meta.layer (id, project_id, name, kind,
				    wms_service_url, wms_layers, wms_image_format, wms_queryable, srid)
				VALUES (:id, :projectId, 'Kaputt', 'WMS',
				    'https://example.test/wms', ARRAY['x'], 'image/png', true, 25832)
				""")).as("a WMS layer with a leftover srid").isTrue();
	}

	@Test
	void v9RejectsAMapImageLayerWithoutAServiceUrlOrAnEmptyLayerList() {
		migrateTo("9");
		UUID projectId = insertProject();

		assertThat(insertRejected(projectId, """
				INSERT INTO gis_meta.layer (id, project_id, name, kind, wms_layers, wms_image_format, wms_queryable)
				VALUES (:id, :projectId, 'Kaputt', 'WMS', ARRAY['x'], 'image/png', true)
				""")).as("no service URL").isTrue();

		assertThat(insertRejected(projectId, """
				INSERT INTO gis_meta.layer (id, project_id, name, kind, wms_service_url, wms_layers, wms_image_format, wms_queryable)
				VALUES (:id, :projectId, 'Kaputt', 'WMS', 'https://example.test/wms', ARRAY[]::text[], 'image/png', true)
				""")).as("an empty layer array").isTrue();

		assertThat(insertRejected(projectId, """
				INSERT INTO gis_meta.layer (id, project_id, name, kind, wms_service_url, wms_layers, wms_queryable)
				VALUES (:id, :projectId, 'Kaputt', 'WMS', 'https://example.test/wms', ARRAY['x'], true)
				""")).as("no image format").isTrue();
	}

	@Test
	void v9RejectsAnUnknownKindToken() {
		migrateTo("9");
		UUID projectId = insertProject();

		assertThat(insertRejected(projectId, """
				INSERT INTO gis_meta.layer (id, project_id, name, kind, table_name, geometry_type, srid)
				VALUES (:id, :projectId, 'Kaputt', 'RASTER', :tableName, 'MULTIPOLYGON', 25832)
				""")).isTrue();
	}

	/** Sanity check on the probe itself: an empty database migrates to V9 unchanged. */
	@Test
	void anEmptyDatabaseStillMigratesAllTheWay() {
		migrateTo("9");

		assertThat(probeJdbc.sql("SELECT count(*) FROM gis_meta.layer").query(Integer.class).single()).isZero();
		assertThat(probeJdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'gis_meta' AND table_name = 'layer' AND column_name = 'kind'
				""").query(Integer.class).single()).isOne();
	}

	// --- helpers ------------------------------------------------------------------------

	private void migrateTo(String version) {
		Flyway.configure()
				.dataSource(probeDataSource)
				.schemas("gis_meta")
				.defaultSchema("gis_meta")
				.locations("classpath:db/migration")
				.target(version)
				.load()
				.migrate();
	}

	private String probeJdbcUrl() {
		String url = container.getJdbcUrl();
		int lastSlash = url.lastIndexOf('/');
		int query = url.indexOf('?', lastSlash);
		String suffix = query < 0 ? "" : url.substring(query);
		return url.substring(0, lastSlash + 1) + PROBE_DATABASE + suffix;
	}

	private UUID insertProject() {
		UUID id = UUID.randomUUID();
		probeJdbc.sql("INSERT INTO gis_meta.project (id, name, srid) VALUES (:id, :name, 25832)")
				.params(Map.of("id", id, "name", "Migrationsprobe"))
				.update();
		return id;
	}

	private UUID insertVectorLayer(UUID projectId, String name, int zIndex) {
		UUID id = UUID.randomUUID();
		probeJdbc.sql("""
				INSERT INTO gis_meta.layer (id, project_id, name, table_name, geometry_type, srid, z_index)
				VALUES (:id, :projectId, :name, :tableName, 'MULTIPOLYGON', 25832, :zIndex)
				""")
				.param("id", id)
				.param("projectId", projectId)
				.param("name", name)
				.param("tableName", SqlIdentifier.tableName(id))
				.param("zIndex", zIndex)
				.update();
		return id;
	}

	/** Whether a CHECK constraint refuses {@code sql}, run with a fresh id/tableName bound in. */
	private boolean insertRejected(UUID projectId, String sql) {
		UUID id = UUID.randomUUID();
		try {
			probeJdbc.sql(sql)
					.param("id", id)
					.param("projectId", projectId)
					.param("tableName", SqlIdentifier.tableName(id))
					.update();
			return false;
		}
		catch (RuntimeException expected) {
			return true;
		}
	}
}
