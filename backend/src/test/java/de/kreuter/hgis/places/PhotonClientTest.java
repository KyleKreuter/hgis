package de.kreuter.hgis.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.kreuter.hgis.places.dto.PlaceDtos;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Checked against a real Photon response (photon.komoot.io, 2026-08-15, {@code
 * q=Eickhoffweg} -- the same street CONTRACT.md's own worked example uses) rather than a
 * hand-written one, the same "fetch once, fixture from then on" rule
 * {@code PlaceGmlReaderTest} follows for Hamburg's WFS.
 */
class PhotonClientTest {

	private static String fixture(String name) {
		try (InputStream in = PhotonClientTest.class.getResourceAsStream("/places/" + name)) {
			if (in == null) {
				throw new IllegalStateException("Test fixture missing: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private record Harness(PhotonClient client, MockRestServiceServer server) {
	}

	private static Harness harness(boolean enabled) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		PhotonProperties properties = new PhotonProperties("https://photon.test/api/", enabled);
		return new Harness(new PhotonClient(builder.build(), properties, new ObjectMapper()), server);
	}

	@Test
	@DisplayName("a real Photon response parses into name, context (city+postcode) and kind=street for a highway")
	void parsesARealStreetResponse() {
		Harness h = harness(true);
		h.server().expect(requestTo(allOf(containsString("q=Eickhoffweg"), containsString("limit=5"))))
				.andRespond(withSuccess(fixture("photon_eickhoffweg.json"), MediaType.APPLICATION_JSON));

		List<PlaceDtos.Result> results = h.client().search("Eickhoffweg", 5);

		assertThat(results).hasSize(5);
		assertThat(results).allSatisfy(r -> {
			assertThat(r.name()).startsWith("Eickhoff");
			assertThat(r.source()).isEqualTo("photon");
			assertThat(r.kind()).isEqualTo("street");
		});

		PlaceDtos.Result hamburgHit = results.stream()
				.filter(r -> r.context() != null && r.context().contains("Hamburg")).findFirst().orElseThrow();
		assertThat(hamburgHit.context()).isEqualTo("Hamburg, 22041");
		assertThat(hamburgHit.lng()).isCloseTo(10.0938256, within(0.0000001));
		assertThat(hamburgHit.lat()).isCloseTo(53.5768111, within(0.0000001));
		h.server().verify();
	}

	@Test
	@DisplayName("a non-street hit (osm_key != highway) is kind=place, per CONTRACT.md")
	void nonStreetHitIsKindPlace() {
		Harness h = harness(true);
		h.server().expect(requestTo(containsString("q=Elbphilharmonie")))
				.andRespond(withSuccess(fixture("photon_elbphilharmonie.json"), MediaType.APPLICATION_JSON));

		List<PlaceDtos.Result> results = h.client().search("Elbphilharmonie", 3);

		assertThat(results).isNotEmpty();
		assertThat(results.get(0).kind()).isEqualTo("place");
		assertThat(results.get(0).context()).isEqualTo("Hamburg, 20457");
		h.server().verify();
	}

	@Test
	@DisplayName("disabled Photon makes no network call and answers with an empty list")
	void disabledPhotonMakesNoCall() {
		Harness h = harness(false);
		// No expectations registered on purpose -- any HTTP call made by mistake fails the
		// test loudly instead of quietly reaching the real network.

		assertThat(h.client().search("Reeperbahn", 5)).isEmpty();
		h.server().verify();
	}

	@Test
	@DisplayName("CONTRACT.md: Photon failing (a 5xx here, a timeout live) never throws -- it answers with an empty list")
	void aServerErrorIsSwallowedNotThrown() {
		Harness h = harness(true);
		h.server().expect(requestTo(containsString("q=Reeperbahn"))).andRespond(withServerError());

		assertThat(h.client().search("Reeperbahn", 5)).isEmpty();
	}

	@Test
	@DisplayName("results without a name are dropped -- CONTRACT.md's name field is required")
	void unnamedFeaturesAreDropped() {
		Harness h = harness(true);
		String body = """
				{"type":"FeatureCollection","features":[
				  {"type":"Feature","properties":{"osm_key":"highway"},"geometry":{"type":"Point","coordinates":[10.0,53.5]}},
				  {"type":"Feature","properties":{"name":"Testweg","osm_key":"highway"},"geometry":{"type":"Point","coordinates":[10.0,53.5]}}
				]}
				""";
		h.server().expect(requestTo(containsString("q=Testweg")))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		List<PlaceDtos.Result> results = h.client().search("Testweg", 5);

		assertThat(results).extracting(PlaceDtos.Result::name).containsExactly("Testweg");
	}
}
