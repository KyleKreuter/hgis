package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
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
 * Proves that V7 adds the Geoportal provenance columns without touching existing rows
 * (CONTRACT.md phase 23.7), the same way {@link ClipModeMigrationTest} proves it for V6:
 * an empty schema has nothing to lose, so the interesting case is a database that already
 * holds layers migrated only as far as V6.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LayerSourceMigrationTest {

	private static final String PROBE_DATABASE = "layer_source_migration_probe";

	private final PostgreSQLContainer container;
	private final JdbcClient adminJdbc;

	private DataSource probeDataSource;
	private JdbcClient probeJdbc;

	LayerSourceMigrationTest(@Autowired PostgreSQLContainer container, @Autowired DataSource dataSource) {
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
	void v7AddsEightNullableColumnsAndLeavesExistingLayersUntouched() {
		migrateTo("6");

		UUID projectId = insertProject();
		UUID layerId = insertLayer(projectId, "Bestandslayer", 0);

		migrateTo("7");

		Map<String, Object> row = probeJdbc.sql("""
				SELECT name, source_attribution, source_license_name, source_license_url,
				       source_dataset_uri, source_metadata_url, source_dataset_id,
				       source_feature_id_field, source_fetched_at
				FROM gis_meta.layer WHERE id = :id
				""")
				.param("id", layerId)
				.query()
				.singleRow();

		assertThat(row.get("name")).as("V7 must not rewrite any existing column").isEqualTo("Bestandslayer");
		assertThat(row.get("source_attribution")).isNull();
		assertThat(row.get("source_license_name")).isNull();
		assertThat(row.get("source_license_url")).isNull();
		assertThat(row.get("source_dataset_uri")).isNull();
		assertThat(row.get("source_metadata_url")).isNull();
		assertThat(row.get("source_dataset_id")).isNull();
		assertThat(row.get("source_feature_id_field")).isNull();
		assertThat(row.get("source_fetched_at")).isNull();
	}

	@Test
	void v7LetsANewGeoportalLayerFillEveryColumn() {
		migrateTo("7");
		UUID projectId = insertProject();
		UUID layerId = insertLayer(projectId, "Straßenbäume", 0);

		probeJdbc.sql("""
				UPDATE gis_meta.layer SET
				    source_attribution = :attribution,
				    source_license_name = :licenseName,
				    source_license_url = :licenseUrl,
				    source_dataset_uri = :datasetUri,
				    source_metadata_url = :metadataUrl,
				    source_dataset_id = :datasetId,
				    source_feature_id_field = :featureIdField,
				    source_fetched_at = now()
				WHERE id = :id
				""")
				.param("attribution", "Freie und Hansestadt Hamburg, Behörde für Umwelt, Klima, Energie und Agrarwirtschaft")
				.param("licenseName", "Datenlizenz Deutschland – Namensnennung – Version 2.0")
				.param("licenseUrl", "https://www.govdata.de/dl-de/by-2-0")
				.param("datasetUri", "https://registry.gdi-de.org/id/de.hh/abc")
				.param("metadataUrl", "https://metaver.de/trefferanzeige?docuuid=abc")
				.param("datasetId", "strassenbaumkataster/strassenbaumkataster_hh")
				.param("featureIdField", "gid")
				.param("id", layerId)
				.update();

		String featureIdField = probeJdbc.sql("SELECT source_feature_id_field FROM gis_meta.layer WHERE id = :id")
				.param("id", layerId)
				.query(String.class)
				.single();
		assertThat(featureIdField).isEqualTo("gid");
	}

	/** Sanity check on the probe itself: an empty database migrates to V7 unchanged. */
	@Test
	void anEmptyDatabaseStillMigratesAllTheWay() {
		migrateTo("7");

		assertThat(probeJdbc.sql("SELECT count(*) FROM gis_meta.layer").query(Integer.class).single()).isZero();
		assertThat(probeJdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'gis_meta' AND table_name = 'layer' AND column_name = 'source_feature_id_field'
				""").query(Integer.class).single()).isOne();
	}

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

	private UUID insertLayer(UUID projectId, String name, int zIndex) {
		UUID id = UUID.randomUUID();
		probeJdbc.sql("""
				INSERT INTO gis_meta.layer (id, project_id, name, table_name, geometry_type, srid, z_index)
				VALUES (:id, :projectId, :name, :tableName, 'MULTIPOLYGON', 25832, :zIndex)
				""")
				.param("id", id)
				.param("projectId", projectId)
				.param("name", name)
				.param("tableName", "layer_" + id.toString().replace("-", ""))
				.param("zIndex", zIndex)
				.update();
		return id;
	}
}
