package de.kreuter.hgis.places;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Fetches Hamburg's own WFS (CONTRACT.md's "Hamburg (Abzug)" section) and hands the
 * response straight to {@link PlaceGmlReader}, parsed while it streams -- the same shape
 * {@code geoportal.CatalogLoader} uses for its 7.6&nbsp;MB service directory: the response
 * body is only valid for the lifetime of {@link RestClient.ResponseSpec#exchange}'s
 * callback, so the parse has to happen inside it rather than after the connection is
 * released.
 */
@Component
class HamburgPlaceFetcher {

	private static final String BASE_URL = "https://geodienste.hamburg.de/HH_WFS_GAGES";

	/**
	 * CONTRACT.md's own URL. 10000 comfortably covers all of Hamburg's streets --
	 * measured live, {@code RESULTTYPE=hits} answers {@code numberMatched="9534"} -- so
	 * this is headroom against future growth, not a page size the reader has to work
	 * around.
	 */
	private static final int COUNT = 10_000;

	private final RestClient restClient;
	private final PlaceGmlReader gmlReader;

	HamburgPlaceFetcher(RestClient hamburgWfsRestClient, PlaceGmlReader gmlReader) {
		this.restClient = hamburgWfsRestClient;
		this.gmlReader = gmlReader;
	}

	List<ParsedPlace> fetchStrassen() {
		return fetchAndParse("dog:Strassen", gmlReader::readStrassen);
	}

	List<ParsedPlace> fetchOrtsteile() {
		return fetchAndParse("dog:Ortsteile", gmlReader::readOrtsteile);
	}

	private List<ParsedPlace> fetchAndParse(String typeName,
			java.util.function.Function<InputStream, List<ParsedPlace>> parse) {
		URI uri = UriComponentsBuilder.fromUriString(BASE_URL)
				.queryParam("SERVICE", "WFS")
				.queryParam("VERSION", "2.0.0")
				.queryParam("REQUEST", "GetFeature")
				.queryParam("TYPENAMES", typeName)
				.queryParam("COUNT", COUNT)
				.build()
				.toUri();

		return restClient.get().uri(uri).exchange((request, response) -> {
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new PlaceRefreshException(
						"Der WFS-Dienst von Hamburg antwortete mit " + response.getStatusCode() + " für " + typeName);
			}
			try (InputStream body = response.getBody()) {
				return parse.apply(body);
			}
		});
	}
}
