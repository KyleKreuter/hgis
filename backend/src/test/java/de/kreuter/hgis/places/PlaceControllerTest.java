package de.kreuter.hgis.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
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
import java.util.Map;
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

	// --- the digit rule --------------------------------------------------------------------

	@Test
	@DisplayName("a term with a digit finds the address -- the house-number contract's own acceptance case")
	void aTermWithADigitFindsTheAddress() throws Exception {
		seedPlace("Eickhoffweg", "Wandsbek, 22041", "street", 10.0937, 53.5769);
		seedPlace("Eickhoffweg 12", "Wandsbek, 22041", "address", 10.0936, 53.5769);

		mvc.perform(get("/api/places").param("q", "Eickhoffweg 12"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places.length()").value(1))
				.andExpect(jsonPath("$.places[0].name").value("Eickhoffweg 12"))
				.andExpect(jsonPath("$.places[0].context").value("Wandsbek, 22041"))
				.andExpect(jsonPath("$.places[0].kind").value("address"))
				.andExpect(jsonPath("$.places[0].source").value("hamburg"))
				.andExpect(jsonPath("$.places[0].lng").value(10.0936))
				.andExpect(jsonPath("$.places[0].lat").value(53.5769));
	}

	@Test
	@DisplayName("an address with a letter suffix is found by typing it -- \"Eickhoffweg 1a\" is not \"Eickhoffweg 1\"")
	void anAddressWithALetterSuffixIsFound() throws Exception {
		seedPlace("Eickhoffweg 1", "Wandsbek, 22041", "address", 10.09, 53.57);
		seedPlace("Eickhoffweg 1a", "Wandsbek, 22041", "address", 10.093, 53.577);

		mvc.perform(get("/api/places").param("q", "Eickhoffweg 1a"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places.length()").value(1))
				.andExpect(jsonPath("$.places[0].name").value("Eickhoffweg 1a"));
	}

	/**
	 * The case the whole digit rule exists for. Without it, 302393 addresses share one table
	 * with 9936 streets, so a street name typed without a number answers with house numbers
	 * in that street instead of the street -- and there is no {@code limit} large enough to
	 * make that useful. The three addresses seeded here would fill the answer entirely;
	 * "Hauptstra" has to come back with the two streets and nothing else.
	 */
	@Test
	@DisplayName("a term without a digit finds streets only -- the house numbers in them stay out of the way")
	void aTermWithoutADigitFindsNoAddresses() throws Exception {
		seedPlace("Billstedter Hauptstraße", "Billstedt, 22111", "street", 10.1, 53.55);
		seedPlace("Hummelsbüttler Hauptstraße", "Hummelsbüttel, 22339", "street", 10.05, 53.64);
		seedPlace("Billstedter Hauptstraße 1", "Billstedt, 22111", "address", 10.1, 53.55);
		seedPlace("Billstedter Hauptstraße 2", "Billstedt, 22111", "address", 10.1, 53.55);
		seedPlace("Billstedter Hauptstraße 3", "Billstedt, 22111", "address", 10.1, 53.55);

		mvc.perform(get("/api/places").param("q", "Hauptstra"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places.length()").value(2))
				.andExpect(jsonPath("$.places[*].kind").value(everyItem(is("street"))));
	}

	@Test
	@DisplayName("a district is still found without a digit -- the rule keeps addresses out, not everything else")
	void aDistrictIsStillFoundWithoutADigit() throws Exception {
		seedPlace("Hamburg-Altstadt", null, "district", 10.0, 53.55);

		mvc.perform(get("/api/places").param("q", "Altstadt"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places.length()").value(1))
				.andExpect(jsonPath("$.places[0].kind").value("district"));
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
		// hauskoordinaten_eickhoffweg.xml: four house numbers.
		assertThat(count).isEqualTo(26);

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

	@Test
	@DisplayName("refresh: the house numbers land as kind=address and are then findable through the search, end to end")
	void refreshWritesAddressesThatAreThenFindable() throws Exception {
		expectSuccessfulWfsFetch();

		String body = mvc.perform(post("/api/places/refresh"))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		awaitSucceeded(mapper.readTree(body).get("id").asString());

		Integer addresses = jdbc.sql("SELECT count(*) FROM gis_meta.place WHERE kind = 'address'")
				.query(Integer.class).single();
		assertThat(addresses).isEqualTo(4); // hauskoordinaten_eickhoffweg.xml

		mvc.perform(get("/api/places").param("q", "Eickhoffweg 12"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places[0].name").value("Eickhoffweg 12"))
				.andExpect(jsonPath("$.places[0].context").value("Wandsbek, 22041"))
				.andExpect(jsonPath("$.places[0].kind").value("address"));

		mockHamburgWfsServer.verify();
	}

	/**
	 * The count Hamburg answers is taken before the first page and is only ever a starting
	 * point -- a paged request cannot report one of its own (it says {@code
	 * numberMatched="unknown"}), so the paging loop has to be able to run without it and
	 * stop at the first empty page instead. This drives that path: the count is "unknown",
	 * one page of four addresses arrives, the next page is empty.
	 */
	@Test
	@DisplayName("refresh: with no count from Hamburg, paging runs until a page comes back empty")
	void refreshPagesUntilAnEmptyPageWhenTheCountIsUnknown() throws Exception {
		mockHamburgWfsServer.expect(requestTo(containsString("TYPENAMES=dog:Strassen")))
				.andRespond(withSuccess(fixture("strassen_akeleiweg.xml"), MediaType.TEXT_XML));
		mockHamburgWfsServer.expect(requestTo(containsString("TYPENAMES=dog:Ortsteile")))
				.andRespond(withSuccess(fixture("ortsteile_sample20.xml"), MediaType.TEXT_XML));
		mockHamburgWfsServer.expect(requestTo(containsString("RESULTTYPE=hits")))
				.andRespond(withSuccess(fixture("hauskoordinaten_hits_unbekannt.xml"), MediaType.TEXT_XML));
		mockHamburgWfsServer.expect(requestTo(containsString("STARTINDEX=0")))
				.andRespond(withSuccess(fixture("hauskoordinaten_eickhoffweg.xml"), MediaType.TEXT_XML));
		mockHamburgWfsServer.expect(requestTo(containsString("STARTINDEX=10000")))
				.andRespond(withSuccess(fixture("hauskoordinaten_leer.xml"), MediaType.TEXT_XML));

		String body = mvc.perform(post("/api/places/refresh")).andReturn().getResponse().getContentAsString();
		awaitSucceeded(mapper.readTree(body).get("id").asString());

		Integer count = jdbc.sql("SELECT count(*) FROM gis_meta.place").query(Integer.class).single();
		assertThat(count).isEqualTo(26); // 2 street segments + 20 districts + 4 addresses
		mockHamburgWfsServer.verify();
	}

	@Test
	@DisplayName("refresh: the job reports what it wrote, broken down by kind, and its progress counts the addresses too")
	void refreshReportsItsProgressAndWhatItWrote() throws Exception {
		expectSuccessfulWfsFetch();

		String body = mvc.perform(post("/api/places/refresh")).andReturn().getResponse().getContentAsString();
		String jobId = mapper.readTree(body).get("id").asString();
		awaitSucceeded(jobId);

		Map<String, Object> job = jdbc.sql("""
				SELECT message, processed_count, total_count FROM gis_meta.job WHERE id = :id
				""").param("id", java.util.UUID.fromString(jobId)).query().singleRow();

		assertThat((String) job.get("message")).isEqualTo("26 Orte aktualisiert (2 Straßen, 20 Ortsteile, 4 Adressen)");
		assertThat(((Number) job.get("processed_count")).longValue()).isEqualTo(26L);
		assertThat(((Number) job.get("total_count")).longValue()).isEqualTo(26L);
	}

	private void expectSuccessfulWfsFetch() {
		mockHamburgWfsServer.expect(requestTo(containsString("TYPENAMES=dog:Strassen")))
				.andRespond(withSuccess(fixture("strassen_akeleiweg.xml"), MediaType.TEXT_XML));
		mockHamburgWfsServer.expect(requestTo(containsString("TYPENAMES=dog:Ortsteile")))
				.andRespond(withSuccess(fixture("ortsteile_sample20.xml"), MediaType.TEXT_XML));
		mockHamburgWfsServer.expect(requestTo(containsString("RESULTTYPE=hits")))
				.andRespond(withSuccess(fixture("hauskoordinaten_hits_vier.xml"), MediaType.TEXT_XML));
		// PROPERTYNAME asserted literally, not just as "some parameter": without it every
		// address also carries its boundary polygon and the extract grows from roughly
		// 318 MB to roughly 2.1 GB (measured 2026-08-15). It is not an optimisation that
		// may quietly fall away.
		mockHamburgWfsServer.expect(requestTo(allOf(
						containsString("TYPENAMES=gages:Hauskoordinaten"),
						containsString("STARTINDEX=0"),
						containsString("PROPERTYNAME=iso19112:geographicIdentifier,gages:position"))))
				.andRespond(withSuccess(fixture("hauskoordinaten_eickhoffweg.xml"), MediaType.TEXT_XML));
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
