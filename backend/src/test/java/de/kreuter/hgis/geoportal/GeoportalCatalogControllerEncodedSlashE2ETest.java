package de.kreuter.hgis.geoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.geoportal.dto.GeoportalDtos;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A dataset id can carry a literal {@code /} (see {@link GeoportalCatalogController}'s own
 * class Javadoc), so a client that URL-encodes it correctly -- as {@code
 * elektrobusdisposition%2Flinienranking} -- sends the octet {@code %2F}. Measured live
 * against this backend before {@link GeoportalEncodedSlashConfig} existed: the request
 * never reached this controller, or Spring, or even the dispatcher servlet. Embedded
 * Tomcat's connector rejected it with a bare, English, connector-generated 400 page, which
 * is why this has to be an end-to-end test against a real running server -- {@code
 * MockMvc} builds a {@code MockHttpServletRequest} directly and never runs the request
 * through Tomcat's own HTTP parsing, so it cannot see this rejection at all (same reasoning
 * as {@code TileControllerBrokenPipeE2ETest}).
 *
 * <p>{@link GeoportalDatasetService} is mocked so this test proves only the routing layer
 * -- the connector accepts the encoded slash, and Spring hands the controller the id with
 * it already decoded back to a literal {@code /} -- without depending on Geoportal Hamburg
 * being reachable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class GeoportalCatalogControllerEncodedSlashE2ETest {

	@LocalServerPort
	private int port;

	@MockitoBean
	private GeoportalDatasetService service;

	@Test
	@DisplayName("a URL-encoded slash in the dataset id reaches the controller, decoded, instead of a bare Tomcat 400")
	void encodedSlashInIdReachesTheControllerDecoded() throws Exception {
		given(service.detail("elektrobusdisposition/linienranking")).willReturn(new GeoportalDtos.DatasetDetail(
				"elektrobusdisposition/linienranking", "Elektrobusdisposition Linienranking", null, "FEATURES",
				"BVM", "Verkehr", 1L, new double[] { 9.9, 53.5, 10.0, 53.6 }, "Freie und Hansestadt Hamburg",
				GeoportalLicense.NAME, GeoportalLicense.URL, null, null, 25832, "id", java.util.List.of(), 1,
				java.util.List.of(), null));

		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port
						+ "/api/geoportal/datasets/elektrobusdisposition%2Flinienranking"))
				.GET().build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).as("no longer Tomcat's bare 400 for the encoded slash").isEqualTo(200);
		assertThat(response.body()).contains("\"id\":\"elektrobusdisposition/linienranking\"");
		verify(service).detail("elektrobusdisposition/linienranking");
	}
}
