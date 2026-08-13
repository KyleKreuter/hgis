package de.kreuter.hgis.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that V8 adds its index to a job table that already holds rows, and adds the index
 * it says it does.
 *
 * <p>The gap this closes is the one {@code ClipModeMigrationTest} describes: every other
 * test here runs against a database Flyway migrated from nothing in one go, so a migration
 * only ever meets an empty table. V8 is a {@code CREATE INDEX} on a column that has been
 * carrying values since V1, which is exactly the case none of those tests reach.
 *
 * <p>What is checked is not that the statement runs -- that much a syntax error would
 * catch. It is that the index covers the rows the foreign key has to look for and no
 * others: it is declared partial, and a partial index that excludes the wrong rows is
 * still a valid index, just one that quietly leaves the sequential scan it was added to
 * remove. So the plan is asked directly, on data that predates the migration.
 *
 * <p>Half-migrated database, second database inside the shared container, dropped
 * afterwards -- the mechanics and the reasons are the same as in {@code
 * ClipModeMigrationTest}, which explains them in full.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobOutputLayerIndexMigrationTest {

	/** Its own database inside the shared container, created and dropped per test. */
	private static final String PROBE_DATABASE = "job_output_layer_index_probe";

	private static final String INDEX_NAME = "job_output_layer_idx";

	private final PostgreSQLContainer container;
	private final JdbcClient adminJdbc;

	private DataSource probeDataSource;
	private JdbcClient probeJdbc;

	JobOutputLayerIndexMigrationTest(@Autowired PostgreSQLContainer container,
			@Autowired DataSource dataSource) {
		this.container = container;
		this.adminJdbc = JdbcClient.create(dataSource);
	}

	@BeforeEach
	void createProbeDatabase() {
		// CREATE DATABASE cannot run inside a transaction, hence the plain JdbcClient
		// against the shared connection rather than anything transactional.
		adminJdbc.sql("DROP DATABASE IF EXISTS " + PROBE_DATABASE).update();
		adminJdbc.sql("CREATE DATABASE " + PROBE_DATABASE).update();

		probeDataSource = DataSourceBuilder.create()
				.url(probeJdbcUrl())
				.username(container.getUsername())
				.password(container.getPassword())
				.build();
		probeJdbc = JdbcClient.create(probeDataSource);

		// The same infrastructure db/testcontainers-init.sql provides for the shared
		// database: Flyway owns neither the extension nor the two schemas.
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
				// A leaked pool would only keep the DROP below from succeeding; the
				// container dies with the test run either way.
			}
		}
		adminJdbc.sql("DROP DATABASE IF EXISTS " + PROBE_DATABASE + " WITH (FORCE)").update();
	}

	@Test
	@DisplayName("V8 leaves every job it finds exactly as it was")
	void v8KeepsTheJobsThatWereAlreadyThere() {
		migrateTo("7");

		UUID projectId = insertProject();
		UUID layerId = insertLayer(projectId, "Importiert");
		UUID producing = insertJob(projectId, "SUCCEEDED", layerId);
		UUID pending = insertJob(projectId, "PENDING", null);

		migrateTo("8");

		assertThat(outputLayerOf(producing)).isEqualTo(layerId);
		assertThat(outputLayerOf(pending)).isNull();
		assertThat(probeJdbc.sql("SELECT count(*) FROM gis_meta.job").query(Integer.class).single())
				.isEqualTo(2);
	}

	/**
	 * The point of the whole migration: a lookup by {@code output_layer_id} -- the one the
	 * foreign key runs before every layer delete -- must reach the index rather than the
	 * table. Asked of a row written before V8 ran, since those are the rows that made the
	 * scan expensive in the first place.
	 *
	 * <p>Reaching the index is not enough, hence the second assertion. A partial index whose
	 * predicate names {@code output_layer_id} is usable for this query whatever it is keyed
	 * on -- PostgreSQL would happily read all of it and apply the comparison afterwards as a
	 * filter, which names the index in the plan while doing the very scan the migration
	 * exists to avoid. Only an {@code Index Cond} means the lookup went through the key.
	 */
	@Test
	@DisplayName("a lookup by output_layer_id reaches the index, on rows written before V8")
	void v8IndexesTheColumnTheForeignKeyLooksUp() throws SQLException {
		migrateTo("7");

		UUID projectId = insertProject();
		UUID layerId = insertLayer(projectId, "Importiert");
		insertJob(projectId, "SUCCEEDED", layerId);

		migrateTo("8");

		List<String> plan = planFor("SELECT id FROM gis_meta.job WHERE output_layer_id = '" + layerId + "'");

		assertThat(plan).anyMatch(line -> line.contains(INDEX_NAME));
		assertThat(plan)
				.as("the lookup has to be the index condition, not a filter applied after reading it")
				.anyMatch(line -> line.contains("Index Cond") && line.contains("output_layer_id"));
	}

	/**
	 * The other half of "partial": the rows it leaves out have to stay left out. A job
	 * without an output layer is the common case -- every PENDING one, every duplication --
	 * and an index that carried them all would cost writes for a lookup the foreign key
	 * never makes.
	 */
	@Test
	@DisplayName("the index covers only the jobs that produced a layer")
	void v8IndexesNothingForJobsWithoutALayer() throws SQLException {
		migrateTo("7");

		UUID projectId = insertProject();
		insertJob(projectId, "PENDING", null);

		migrateTo("8");

		assertThat(probeJdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = :name")
				.param("name", INDEX_NAME)
				.query(String.class)
				.single())
				.contains("(output_layer_id)")
				.contains("WHERE (output_layer_id IS NOT NULL)");
		assertThat(planFor("SELECT id FROM gis_meta.job WHERE output_layer_id IS NULL"))
				.as("a partial index cannot answer a question about the rows it excludes")
				.noneMatch(line -> line.contains(INDEX_NAME));
	}

	/**
	 * The behaviour the index is not allowed to change. {@code ON DELETE SET NULL} is what
	 * keeps a failed job readable after its half-built layer was dropped, and it has to keep
	 * working for a job row that predates the index.
	 */
	@Test
	@DisplayName("deleting a layer still empties the job that produced it")
	void v8LeavesTheForeignKeyBehaviourAlone() {
		migrateTo("7");

		UUID projectId = insertProject();
		UUID layerId = insertLayer(projectId, "Wird gelöscht");
		UUID producing = insertJob(projectId, "FAILED", layerId);

		migrateTo("8");
		probeJdbc.sql("DELETE FROM gis_meta.layer WHERE id = :id").param("id", layerId).update();

		assertThat(outputLayerOf(producing)).isNull();
		assertThat(probeJdbc.sql("SELECT status FROM gis_meta.job WHERE id = :id")
				.param("id", producing)
				.query(String.class)
				.single())
				.as("the job itself survives its layer, which is why the key sets NULL rather than cascading")
				.isEqualTo("FAILED");
	}

	/** Guards the assumption the tests above rest on: V7 has no such index yet. */
	@Test
	void v7DoesNotHaveTheIndex() {
		migrateTo("7");

		assertThat(indexCount()).isZero();

		migrateTo("8");

		assertThat(indexCount()).isOne();
	}

	/** Sanity check on the probe itself: an empty database migrates all the way. */
	@Test
	void anEmptyDatabaseStillMigratesAllTheWay() {
		migrateTo("8");

		assertThat(indexCount()).isOne();
		assertThat(probeJdbc.sql("SELECT count(*) FROM gis_meta.job").query(Integer.class).single()).isZero();
	}

	/**
	 * The query plan, one line per row, with sequential scans switched off.
	 *
	 * <p>Off because these tables hold a handful of rows, where reading the whole thing is
	 * genuinely cheaper and the planner is right to say so. What is being asked here is
	 * whether the index *can* answer the query, not which way PostgreSQL would rather go
	 * today -- and on the production table, where it holds every import ever run, the two
	 * answers are the same one.
	 *
	 * <p>Both statements go over one connection of their own: the setting lasts for a
	 * session, and a pooled {@code JdbcClient} hands out whichever session is free.
	 */
	private List<String> planFor(String sql) throws SQLException {
		try (Connection connection = probeDataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("SET enable_seqscan = off");

			List<String> plan = new ArrayList<>();
			try (ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
				while (rows.next()) {
					plan.add(rows.getString(1));
				}
			}
			return plan;
		}
	}

	private int indexCount() {
		return probeJdbc.sql("""
				SELECT count(*) FROM pg_indexes
				WHERE schemaname = 'gis_meta' AND indexname = :name
				""")
				.param("name", INDEX_NAME)
				.query(Integer.class)
				.single();
	}

	private UUID outputLayerOf(UUID jobId) {
		return probeJdbc.sql("SELECT output_layer_id FROM gis_meta.job WHERE id = :id")
				.param("id", jobId)
				.query(UUID.class)
				.optional()
				.orElse(null);
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
		// The container's own URL, pointed at the probe database instead of the default
		// one. Splitting on the last slash keeps host, port and any query string intact.
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

	private UUID insertLayer(UUID projectId, String name) {
		UUID id = UUID.randomUUID();
		probeJdbc.sql("""
				INSERT INTO gis_meta.layer (id, project_id, name, table_name, geometry_type, srid)
				VALUES (:id, :projectId, :name, :tableName, 'MULTIPOLYGON', 25832)
				""")
				.param("id", id)
				.param("projectId", projectId)
				.param("name", name)
				.param("tableName", "layer_" + id.toString().replace("-", ""))
				.update();
		return id;
	}

	private UUID insertJob(UUID projectId, String status, UUID outputLayerId) {
		UUID id = UUID.randomUUID();
		probeJdbc.sql("""
				INSERT INTO gis_meta.job (id, project_id, type, status, filename, output_layer_id)
				VALUES (:id, :projectId, 'IMPORT', :status, 'bestand.geojson', :outputLayerId)
				""")
				.param("id", id)
				.param("projectId", projectId)
				.param("status", status)
				.param("outputLayerId", outputLayerId)
				.update();
		return id;
	}
}
