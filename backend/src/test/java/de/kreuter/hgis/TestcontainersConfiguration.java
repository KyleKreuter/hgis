package de.kreuter.hgis;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A plain {@code postgres} image has no PostGIS extension, so V1__catalog.sql's
 * {@code geometry(...)} columns fail to create on it. Every catalog table uses that
 * type, so this has to be the same image docker-compose.yml uses for local
 * development, substituted in as a Postgres-compatible image so
 * {@code PostgreSQLContainer}'s JDBC wiring still applies.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(
				DockerImageName.parse("imresamu/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
				.withInitScript("db/tiles-test-init.sql");
	}

}
