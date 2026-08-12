package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that V6 carries existing clip masks over instead of dropping them.
 *
 * <p>The gap this closes: every other test in this code base runs against a database
 * Flyway migrated in one go, from nothing to the latest version. An empty table has no
 * rows to convert, so a migration that silently loses data passes all of them and only
 * shows up in production. V6 rewrites every stored clip mode
 * ({@code inside -> insideClipped}, {@code outside -> outsideClipped}, CONTRACT.md phase
 * 21), which makes it exactly the kind of migration where that blind spot is expensive.
 *
 * <p>Getting a half-migrated database to write into takes some care. The clip mode
 * columns are pinned to {@code gis_meta} by name inside V1 (the {@code touch_updated_at}
 * trigger function), so migrating a probe into some other schema of the shared database
 * is not an option. A second container is not one either -- {@link
 * TestcontainersConfiguration} explains why there is exactly one. What is left, and what
 * this test does, is a second *database* inside the very same container: same Postgres,
 * same PostGIS, its own {@code gis_meta}, dropped again afterwards.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClipModeMigrationTest {

	/** Its own database inside the shared container, created and dropped per test. */
	private static final String PROBE_DATABASE = "clip_mode_migration_probe";

	private final PostgreSQLContainer container;
	private final JdbcClient adminJdbc;

	private DataSource probeDataSource;
	private JdbcClient probeJdbc;

	ClipModeMigrationTest(@org.springframework.beans.factory.annotation.Autowired PostgreSQLContainer container,
			@org.springframework.beans.factory.annotation.Autowired DataSource dataSource) {
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
		// database, and for the same reason: Flyway does not own the extension or the
		// two schemas, so a fresh database has to bring them itself.
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
	void v6TurnsEveryStoredClipModeIntoItsFourModeSpelling() {
		migrateTo("5");

		UUID projectId = insertProject();
		UUID insideId = insertLayer(projectId, "Innen", 0, "inside");
		UUID outsideId = insertLayer(projectId, "Aussen", 1, "outside");
		UUID plainId = insertLayer(projectId, "Ohne Maske", 2, null);

		migrateTo("6");

		assertThat(clipModeOf(insideId)).isEqualTo("insideClipped");
		assertThat(clipModeOf(outsideId)).isEqualTo("outsideClipped");
		assertThat(clipModeOf(plainId)).isNull();
	}

	/**
	 * The old one-mask-per-project rule lived in {@code LayerService}, never in a
	 * constraint, so a database written before phase 21 can only ever hold one mask --
	 * but nothing stops V6 from having to cope with more, and the check constraint it
	 * installs applies to every row at once. A project carrying both directions proves
	 * the new constraint accepts the values V6 itself just wrote.
	 */
	@Test
	void v6LeavesSeveralMasksOfOneProjectIntact() {
		migrateTo("5");

		UUID projectId = insertProject();
		insertLayer(projectId, "Erste", 0, "inside");
		insertLayer(projectId, "Zweite", 1, "outside");

		migrateTo("6");

		assertThat(probeJdbc.sql("SELECT clip_mode FROM gis_meta.layer WHERE clip_mode IS NOT NULL ORDER BY z_index")
				.query(String.class)
				.list())
				.containsExactly("insideClipped", "outsideClipped");
	}

	/**
	 * The reverse guard: after V6 the old spellings must be rejected outright. Without
	 * this, a stray writer could put {@code 'inside'} back into a column whose readers
	 * have all moved on to the four-mode vocabulary, and the row would render as if it
	 * carried no mode at all.
	 */
	@Test
	void v6RejectsTheOldSpellingsAfterwards() {
		migrateTo("5");
		UUID projectId = insertProject();
		migrateTo("6");

		assertThat(insertRejected(projectId, "inside")).isTrue();
		assertThat(insertRejected(projectId, "outside")).isTrue();
		assertThat(insertRejected(projectId, "insideWhole")).isFalse();
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

	private UUID insertLayer(UUID projectId, String name, int zIndex, String clipMode) {
		UUID id = UUID.randomUUID();
		probeJdbc.sql("""
				INSERT INTO gis_meta.layer (id, project_id, name, table_name, geometry_type, srid, z_index, clip_mode)
				VALUES (:id, :projectId, :name, :tableName, 'MULTIPOLYGON', 25832, :zIndex, :clipMode)
				""")
				.param("id", id)
				.param("projectId", projectId)
				.param("name", name)
				.param("tableName", "layer_" + id.toString().replace("-", ""))
				.param("zIndex", zIndex)
				.param("clipMode", clipMode)
				.update();
		return id;
	}

	private String clipModeOf(UUID layerId) {
		return probeJdbc.sql("SELECT clip_mode FROM gis_meta.layer WHERE id = :id")
				.param("id", layerId)
				.query(String.class)
				.optional()
				.orElse(null);
	}

	/** Whether the check constraint refuses {@code clipMode}. */
	private boolean insertRejected(UUID projectId, String clipMode) {
		try {
			insertLayer(projectId, "Probe " + clipMode, 9, clipMode);
			return false;
		}
		catch (RuntimeException expected) {
			return true;
		}
	}

	/** Guards the assumption every test above rests on: V5 still knows the old spellings. */
	@Test
	void v5StillAcceptsTheOldSpellings() {
		migrateTo("5");
		UUID projectId = insertProject();

		assertThat(insertRejected(projectId, "inside")).isFalse();
		assertThat(insertRejected(projectId, "outside")).isFalse();
		assertThat(insertRejected(projectId, "insideClipped")).isTrue();

		assertThat(probeJdbc.sql("SELECT count(*) FROM gis_meta.flyway_schema_history WHERE version = '6'")
				.query(Integer.class)
				.single())
				.as("V6 must not have run yet")
				.isZero();
	}

	/** Sanity check on the probe itself: an empty database migrates to V6 unchanged. */
	@Test
	void anEmptyDatabaseStillMigratesAllTheWay() {
		migrateTo("6");

		assertThat(probeJdbc.sql("SELECT count(*) FROM gis_meta.layer").query(Integer.class).single()).isZero();
		assertThat(probeJdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'gis_meta' AND table_name = 'layer' AND column_name = 'clip_mode'
				""").query(Integer.class).single()).isOne();
	}

	/** The four-mode vocabulary V6 installs, spelled out so a typo in either place fails here. */
	@Test
	void v6AcceptsExactlyTheFourModes() {
		migrateTo("6");
		UUID projectId = insertProject();

		for (String mode : List.of("insideWhole", "insideClipped", "outsideWhole", "outsideClipped")) {
			assertThat(insertRejected(projectId, mode)).as(mode + " must be accepted").isFalse();
		}
		assertThat(insertRejected(projectId, "inside")).isTrue();
		assertThat(insertRejected(projectId, "clipped")).isTrue();
	}
}
