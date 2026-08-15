package de.kreuter.hgis.places;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * CONTRACT.md's own acceptance case: "Photon wirft einen Fehler, die Hamburger Treffer
 * kommen trotzdem" -- Photon throws an error, the Hamburg hits still arrive. A class of its
 * own rather than a method on {@link PlaceControllerTest}: that class switches Photon off
 * entirely via {@code hgis.places.photon.enabled=false}, and a different property value
 * means a differently configured Spring context -- and therefore, unavoidably, a second
 * Testcontainers instance, the same trade-off {@code GeoportalImportControllerTest}'s own
 * {@code @Primary} RestClient override already accepts (see
 * {@code TestcontainersConfiguration}'s class doc).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PlaceSearchPhotonOutageTest {

	@TestConfiguration
	static class MockPhotonConfig {

		@Bean
		RestClient.Builder mockablePhotonRestClientBuilder() {
			return RestClient.builder();
		}

		@Bean
		MockRestServiceServer mockPhotonServer(RestClient.Builder mockablePhotonRestClientBuilder) {
			return MockRestServiceServer.bindTo(mockablePhotonRestClientBuilder).build();
		}

		/** {@code @Primary} so {@link PhotonClient}'s injection point gets this one instead
		 *  of the real, network-facing bean {@link PhotonHttpClientConfig} declares. Named
		 *  differently from that bean method (also called {@code photonRestClient}) so the
		 *  two coexist as two {@code RestClient} beans rather than colliding by name. */
		@Bean
		@Primary
		RestClient testPhotonRestClient(RestClient.Builder mockablePhotonRestClientBuilder,
				MockRestServiceServer mockPhotonServer) {
			return mockablePhotonRestClientBuilder.build();
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MockRestServiceServer mockPhotonServer;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void resetPlaceTableAndMockServer() {
		jdbc.sql("TRUNCATE TABLE gis_meta.place").update();
		mockPhotonServer.reset();
	}

	@Test
	@DisplayName("CONTRACT.md: Photon failing is 200 with the Hamburg hits, never an error")
	void photonFailingStillReturnsTheHamburgHits() throws Exception {
		jdbc.sql("""
				INSERT INTO gis_meta.place (id, name, context, kind, source, geom)
				VALUES (gen_random_uuid(), 'Hauptstraßenweg', 'Testort, 12345', 'street', 'hamburg',
				        ST_SetSRID(ST_MakePoint(10.0, 53.5), 4326))
				""").update();

		mockPhotonServer.expect(requestTo(Matchers.containsString("q=Hauptstra")))
				.andRespond(withServerError());

		mvc.perform(get("/api/places").param("q", "Hauptstra"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places[0].name").value("Hauptstraßenweg"))
				.andExpect(jsonPath("$.places[0].source").value("hamburg"))
				.andExpect(jsonPath("$.places.length()").value(1));

		mockPhotonServer.verify();
	}
}
