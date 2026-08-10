package de.kreuter.hgis.ingest;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The writer track's own Testcontainers setup, distinct from the plain
 * {@code postgres:latest} container in {@code TestcontainersConfiguration}: table
 * creation, batch inserts and extent computation all rely on the PostGIS extension and
 * the {@code gis_meta}/{@code gis_data} schemas, none of which a vanilla Postgres image
 * provides. The image tag mirrors docker-compose.yml.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgisTestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgisContainer() {
		DockerImageName image = DockerImageName.parse("imresamu/postgis:17-3.5")
				.asCompatibleSubstituteFor("postgres");
		return new PostgreSQLContainer(image)
				.withInitScript("testcontainers/postgis-init.sql");
	}
}
