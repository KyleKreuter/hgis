package de.kreuter.hgis.tiles;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers setup for tile and layer integration tests.
 *
 * The root {@code TestcontainersConfiguration} uses a plain {@code postgres} image,
 * which has no PostGIS extension -- fine for tests that never touch geometry, but the
 * tile query lives and dies by PostGIS functions (ST_TileEnvelope, ST_AsMVT, GiST).
 * This mirrors the image from docker-compose.yml instead, substituted in as a
 * Postgres-compatible image so PostgreSQLContainer's JDBC wiring still applies.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TileTestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgisContainer() {
		return new PostgreSQLContainer(
				DockerImageName.parse("imresamu/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
				.withInitScript("db/tiles-test-init.sql");
	}
}
