package de.kreuter.hgis.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * End to end: HTTP requests against both place endpoints, through the real
 * {@link PlaceSearchService}/{@link HamburgPlaceQuery} and {@link PlaceRefreshService}
 * pipelines, into real PostGIS -- what actually proves V10__place.sql's trigram index and
 * {@code place_search_key} function work from the application's own code path, not only
 * from raw SQL ({@link PlaceMigrationTest} covers that side).
 *
 * <p>Photon is switched off for this whole class ({@code hgis.places.photon.enabled=false})
 * so no test here needs to know about it; {@link PlaceSearchPhotonOutageTest} is the one
 * place that turns it on, with a mocked endpoint, to prove CONTRACT.md's "Photon antwortet
 * nicht ... kein Fehler" -- exactly the same reason {@code GeoportalImportControllerTest}
 * mocks {@code geoportalRestClient} via a {@code @Primary} override rather than talking to
 * the real network.
 *
 * <p>{@code hamburgWfsRestClient} is mocked the same way, for the refresh tests: this is
 * the one class that exercises the real, un-mocked {@link PlaceGmlReader} against a
 * response the test controls, end to end through the job lifecycle a client actually polls.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "hgis.places.photon.enabled=false")
class PlaceControllerTest {

	@TestConfiguration
	static class MockHamburgWfsConfig {

		@Bean
		RestClient.Builder mockableHamburgWfsRestClientBuilder() {
			return RestClient.builder();
		}

		@Bean
		MockRestServiceServer mockHamburgWfsServer(RestClient.Builder mockableHamburgWfsRestClientBuilder) {
			return MockRestServiceServer.bindTo(mockableHamburgWfsRestClientBuilder).build();
		}

		/** {@code @Primary} so the {@code hamburgWfsRestClient} injection point in
		 *  {@code HamburgPlaceFetcher} gets this one instead of the real, network-facing
		 *  bean {@code HamburgWfsHttpClientConfig} declares. Named differently from that
		 *  bean method (which is also called {@code hamburgWfsRestClient}) so the two
		 *  coexist as two {@code RestClient} beans rather than colliding by name -- the
		 *  same shape {@code GeoportalImportControllerTest}'s own override uses. */
		@Bean
		@Primary
		RestClient testHamburgWfsRestClient(RestClient.Builder mockableHamburgWfsRestClientBuilder,
				MockRestServiceServer mockHamburgWfsServer) {
			return mockableHamburgWfsRestClientBuilder.build();
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MockRestServiceServer mockHamburgWfsServer;

	@Autowired
	private JdbcClient jdbc;

	private final ObjectMapper mapper = new ObjectMapper();

	private static String fixture(String name) {
		try (InputStream in = PlaceControllerTest.class.getResourceAsStream("/places/" + name)) {
			if (in == null) {
				throw new IllegalStateException("Test fixture missing: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@BeforeEach
	void resetPlaceTableAndMockServer() {
		jdbc.sql("TRUNCATE TABLE gis_meta.place").update();
		mockHamburgWfsServer.reset();
	}

	// --- GET /api/places -------------------------------------------------------------------

	@Test
	@DisplayName("CONTRACT.md: q with one character is a 400, with the exact message")
	void aOneCharacterQueryIs400() throws Exception {
		mvc.perform(get("/api/places").param("q", "a"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Der Suchbegriff muss mindestens zwei Zeichen haben."));
	}

	@Test
	@DisplayName("CONTRACT.md: no match is 200 with an empty list, not an error")
	void noMatchIsAnEmptyListNotAnError() throws Exception {
		mvc.perform(get("/api/places").param("q", "Nirgendwostraße"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places").isArray())
				.andExpect(jsonPath("$.places").isEmpty());
	}

	@Test
	@DisplayName("a seeded Hamburg row is found end to end through the real trigram query and answers with the contract's exact shape")
	void aSeededHamburgRowIsFoundThroughTheRealQuery() throws Exception {
		seedPlace("Billstedter Hauptstraße", "Billstedt, 22111", "street", 10.1, 53.55);

		mvc.perform(get("/api/places").param("q", "Hauptstra"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places[0].name").value("Billstedter Hauptstraße"))
				.andExpect(jsonPath("$.places[0].context").value("Billstedt, 22111"))
				.andExpect(jsonPath("$.places[0].source").value("hamburg"))
				.andExpect(jsonPath("$.places[0].kind").value("street"))
				.andExpect(jsonPath("$.places[0].lng").value(10.1))
				.andExpect(jsonPath("$.places[0].lat").value(53.55));
	}

	@Test
	@DisplayName("_ in a search term is a literal character, not the LIKE any-single-character wildcard")
	void underscoreInATermIsMatchedLiterally() throws Exception {
		// An unescaped "_" in the pattern would match any single character in its place,
		// so "5_0 Weg" would wrongly also match "5X0 Weg" -- exactly the row this test
		// seeds to tell the two apart.
		seedPlace("5_0 Weg", null, "street", 10.0, 53.5);
		seedPlace("5X0 Weg", null, "street", 10.0, 53.5);

		mvc.perform(get("/api/places").param("q", "5_0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places.length()").value(1))
				.andExpect(jsonPath("$.places[0].name").value("5_0 Weg"));
	}

	@Test
	@DisplayName("limit above 25 is clamped, not rejected")
	void limitAboveTwentyFiveIsClampedNotRejected() throws Exception {
		for (int i = 0; i < 30; i++) {
			seedPlace("Teststraße " + i, null, "street", 10.0, 53.5);
		}

		mvc.perform(get("/api/places").param("q", "Teststra").param("limit", "1000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places.length()").value(25));
	}

	// --- POST /api/places/refresh ------------------------------------------------------------

	@Test
	@DisplayName("refresh: 202 with a Job, the real GML reader and writer run end to end, and the place table is replaced")
	void refreshRunsTheRealPipelineEndToEnd() throws Exception {
		expectSuccessfulWfsFetch();

		String body = mvc.perform(post("/api/places/refresh"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.type").value("PROCESSING"))
				.andReturn().getResponse().getContentAsString();
		String jobId = mapper.readTree(body).get("id").asString();

		awaitSucceeded(jobId);

		Integer count = jdbc.sql("SELECT count(*) FROM gis_meta.place").query(Integer.class).single();
		// strassen_akeleiweg.xml: one street, two postal-code segments.
		// ortsteile_sample20.xml: twenty districts.
		assertThat(count).isEqualTo(22);

		mockHamburgWfsServer.verify();
	}

	@Test
	@DisplayName("a refresh replaces the table rather than appending -- old rows from a previous refresh are gone afterwards")
	void refreshReplacesRatherThanAppends() throws Exception {
		seedPlace("Alte Testfahrt", null, "street", 9.9, 53.4);

		expectSuccessfulWfsFetch();
		String body = mvc.perform(post("/api/places/refresh")).andReturn().getResponse().getContentAsString();
		awaitSucceeded(mapper.readTree(body).get("id").asString());

		Integer stale = jdbc.sql("SELECT count(*) FROM gis_meta.place WHERE name = 'Alte Testfahrt'")
				.query(Integer.class).single();
		assertThat(stale).isZero();
	}

	private void expectSuccessfulWfsFetch() {
		mockHamburgWfsServer.expect(requestTo(containsString("TYPENAMES=dog:Strassen")))
				.andRespond(withSuccess(fixture("strassen_akeleiweg.xml"), MediaType.TEXT_XML));
		mockHamburgWfsServer.expect(requestTo(containsString("TYPENAMES=dog:Ortsteile")))
				.andRespond(withSuccess(fixture("ortsteile_sample20.xml"), MediaType.TEXT_XML));
	}

	private void awaitSucceeded(String jobId) throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			Object status = jdbc.sql("SELECT status FROM gis_meta.job WHERE id = :id")
					.param("id", java.util.UUID.fromString(jobId)).query(String.class).single();
			if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
				assertThat(status).isEqualTo("SUCCEEDED");
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Orte-Abzug wurde binnen 10 s nicht fertig");
	}

	private void seedPlace(String name, String context, String kind, double lng, double lat) {
		jdbc.sql("""
				INSERT INTO gis_meta.place (id, name, context, kind, source, geom)
				VALUES (gen_random_uuid(), :name, :context, :kind, 'hamburg', ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
				""")
				.param("name", name)
				.param("context", context)
				.param("kind", kind)
				.param("lng", lng)
				.param("lat", lat)
				.update();
	}
}
