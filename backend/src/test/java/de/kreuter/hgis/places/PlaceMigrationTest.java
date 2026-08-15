package de.kreuter.hgis.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.kreuter.hgis.TestcontainersConfiguration;
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
 * Proves V10__place.sql on its own probe database, the same way {@code
 * LayerSourceMigrationTest} proves V7: an empty schema has nothing to lose, so the
 * interesting case is a database already migrated to V9, and whether V10 leaves that
 * untouched.
 *
 * <p>Also carries the two acceptance tests CONTRACT.md names explicitly -- the coordinate
 * transform and the "Hauptstra" trigram search -- at the SQL level, directly against the
 * migrated schema and its {@code place_search_key} function and trigram index, independent
 * of the Java search service ({@link PlaceControllerTest} exercises that layer instead).
 * A dedicated probe database, like {@code LayerSourceMigrationTest}'s, is what makes that
 * safe here: every other places test shares one cached Spring context and its one {@code
 * place} table (see {@code TestcontainersConfiguration}'s own class doc on why a second
 * container is not created for that), and this class must not depend on -- or leave behind
 * -- state any of them can see.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PlaceMigrationTest {

	private static final String PROBE_DATABASE = "place_migration_probe";

	private final PostgreSQLContainer container;
	private final JdbcClient adminJdbc;

	private DataSource probeDataSource;
	private JdbcClient probeJdbc;

	PlaceMigrationTest(@Autowired PostgreSQLContainer container, @Autowired DataSource dataSource) {
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
	@DisplayName("V10 adds place without touching an existing layer migrated only as far as V9")
	void v10LeavesExistingDataUntouched() {
		migrateTo("9");

		UUID projectId = insertProject();
		UUID layerId = insertLayer(projectId, "Bestandslayer");

		migrateTo("10");

		String name = probeJdbc.sql("SELECT name FROM gis_meta.layer WHERE id = :id")
				.param("id", layerId).query(String.class).single();
		assertThat(name).as("V10 must not rewrite any existing row").isEqualTo("Bestandslayer");

		Integer placeTableExists = probeJdbc.sql("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_schema = 'gis_meta' AND table_name = 'place'
				""").query(Integer.class).single();
		assertThat(placeTableExists).isEqualTo(1);
	}

	@Test
	@DisplayName("place_kind and place_source reject anything outside their allowed tokens")
	void checkConstraintsRejectUnknownTokens() {
		migrateTo("10");

		assertThatInsertFails("'street'", "'photon'"); // source must be 'hamburg' only
		assertThatInsertFails("'address'", "'hamburg'"); // kind must be one of the three
	}

	private void assertThatInsertFails(String kind, String source) {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> probeJdbc.sql("""
						INSERT INTO gis_meta.place (id, name, kind, source, geom)
						VALUES (gen_random_uuid(), 'Test', %s, %s, ST_SetSRID(ST_MakePoint(10, 53), 4326))
						""".formatted(kind, source))
						.update())
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("CONTRACT.md's own worked example: 572406.785 5937005.370 in 25832 lands in Hamburg, not the Atlantic")
	void theWorkedCoordinateExampleLandsInHamburg() {
		migrateTo("10");

		Map<String, Object> point = probeJdbc.sql("""
				SELECT round(ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(572406.785, 5937005.370), 25832), 4326))::numeric, 4) AS lng,
				       round(ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(572406.785, 5937005.370), 25832), 4326))::numeric, 4) AS lat
				""").query().singleRow();

		// Measured once against this same PostGIS/PROJ combination (imresamu/postgis:17-3.5)
		// and fixed here, per CONTRACT.md's "feste Erwartung auf vier Nachkommastellen".
		assertThat(((Number) point.get("lng")).doubleValue()).isCloseTo(10.0936, within(0.0001));
		assertThat(((Number) point.get("lat")).doubleValue()).isCloseTo(53.5769, within(0.0001));
	}

	@Test
	@DisplayName("CONTRACT.md: the trigram index finds \"Hauptstra\" -- the truncated middle fragment the amtliche WFS's own search cannot")
	void trigramIndexFindsATruncatedMiddleFragment() {
		migrateTo("10");

		insertPlace("Billstedter Hauptstraße", "Billstedt, 22111", "street");
		insertPlace("Alsterdorfer Straße", "Alsterdorf, 22299", "street");
		insertPlace("Reeperbahn", "St. Pauli, 20359", "street");

		List<String> hits = probeJdbc.sql("""
				SELECT name FROM gis_meta.place
				WHERE gis_meta.place_search_key(name) ILIKE gis_meta.place_search_key('%Hauptstra%')
				ORDER BY name
				""").query(String.class).list();

		assertThat(hits).containsExactly("Billstedter Hauptstraße");
	}

	@Test
	@DisplayName("place_search_key folds case and umlauts, so \"strasse\" still finds \"Lüttmoorstraße\"")
	void searchKeyFoldsCaseAndUmlauts() {
		migrateTo("10");
		insertPlace("Lüttmoorstraße", null, "street");

		Boolean matches = probeJdbc.sql(
				"SELECT gis_meta.place_search_key('Lüttmoorstraße') ILIKE gis_meta.place_search_key('%luttmoorstrasse%')")
				.query(Boolean.class).single();

		assertThat(matches).isTrue();
	}

	/**
	 * ß -&gt; ss is not covered by Unicode decomposition the way ä/ö/ü are (ß has none), so
	 * whether it folds at all depends entirely on whether the bundled {@code unaccent.rules}
	 * happens to list it -- a property of the PostgreSQL/PostGIS build, not of {@code
	 * place_search_key}'s own SQL, and not guaranteed by any standard. Measured directly
	 * against this project's own image ({@code imresamu/postgis:17-3.5}, the same one
	 * {@code docker-compose.yml} and {@code TestcontainersConfiguration} use):
	 * {@code /usr/share/postgresql/17/tsearch_data/unaccent.rules} line 43 reads {@code ß ss}.
	 * No code change followed from that -- adding a manual {@code replace(..., 'ß', 'ss')}
	 * would duplicate what the extension already does and read, to the next person, as a
	 * sign that {@code unaccent} were somehow broken here.
	 *
	 * <p>This test exists to hold that property in place rather than to prove it once: {@code
	 * docker-compose.yml} reads its image from {@code ${HGIS_DB_IMAGE:-...}}, swappable from
	 * outside, and the ß rule only entered {@code unaccent.rules} with PostgreSQL 12. A future
	 * image or version change that drops the rule must fail this test loudly -- a search that
	 * silently stops finding "Hauptstraße" for someone who types "Hauptstrasse" is exactly the
	 * kind of regression nobody notices until a user reports "the search doesn't work".
	 */
	@Test
	@DisplayName("place_search_key folds ß and ss onto each other in both directions -- pinned by this project's own PostGIS image, not guaranteed by unaccent in general")
	void searchKeyFoldsSharfesSInBothDirections() {
		migrateTo("10");
		insertPlace("Billstedter Hauptstraße", "Billstedt, 22111", "street"); // stored with ß
		insertPlace("Musterstrasse", null, "street"); // stored with ss

		List<String> foundByTypingSs = probeJdbc.sql("""
				SELECT name FROM gis_meta.place
				WHERE gis_meta.place_search_key(name) ILIKE gis_meta.place_search_key('%hauptstrasse%')
				""").query(String.class).list();
		assertThat(foundByTypingSs).as("\"Hauptstrasse\" (typed with ss) must find \"Hauptstraße\" (stored with ß)")
				.containsExactly("Billstedter Hauptstraße");

		List<String> foundByTypingSharfesS = probeJdbc.sql("""
				SELECT name FROM gis_meta.place
				WHERE gis_meta.place_search_key(name) ILIKE gis_meta.place_search_key('%musterstraße%')
				""").query(String.class).list();
		assertThat(foundByTypingSharfesS).as("\"Musterstraße\" (typed with ß) must find \"Musterstrasse\" (stored with ss)")
				.containsExactly("Musterstrasse");
	}

	private void insertPlace(String name, String context, String kind) {
		probeJdbc.sql("""
				INSERT INTO gis_meta.place (id, name, context, kind, source, geom)
				VALUES (gen_random_uuid(), :name, :context, :kind, 'hamburg',
				        ST_SetSRID(ST_MakePoint(10.0, 53.5), 4326))
				""")
				.param("name", name)
				.param("context", context)
				.param("kind", kind)
				.update();
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

	private UUID insertLayer(UUID projectId, String name) {
		UUID id = UUID.randomUUID();
		probeJdbc.sql("""
				INSERT INTO gis_meta.layer (id, project_id, name, table_name, geometry_type, srid, z_index)
				VALUES (:id, :projectId, :name, :tableName, 'MULTIPOLYGON', 25832, 0)
				""")
				.param("id", id)
				.param("projectId", projectId)
				.param("name", name)
				.param("tableName", "layer_" + id.toString().replace("-", ""))
				.update();
		return id;
	}
}
