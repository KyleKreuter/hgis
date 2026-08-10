package de.kreuter.hgis;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one database for all integration tests.
 *
 * A plain {@code postgres} image has no PostGIS, so every geometry column in
 * V1__catalog.sql fails to create and the context never starts. The image therefore has
 * to match the one used for local development, declared as a Postgres-compatible
 * substitute so {@code PostgreSQLContainer} keeps wiring up the JDBC connection.
 *
 * <p>Deliberately the only such configuration in the code base. Spring caches test
 * contexts by their configuration, so a second class -- even one that is byte for byte
 * equivalent -- yields a second context and therefore a second container. Tests that
 * need extra schema objects create them in their own setup instead of bringing their own
 * init script; the price of a divergent script is a whole extra Postgres per test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** Same multi-arch PostGIS build as docker-compose.yml -- native on Apple Silicon. */
	private static final DockerImageName POSTGIS = DockerImageName
			.parse("imresamu/postgis:17-3.5")
			.asCompatibleSubstituteFor("postgres");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGIS)
				.withInitScript("db/testcontainers-init.sql");
	}

}
