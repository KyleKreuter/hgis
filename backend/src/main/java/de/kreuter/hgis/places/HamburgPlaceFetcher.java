package de.kreuter.hgis.places;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
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

	/**
	 * How many house numbers one {@link #fetchHauskoordinaten} call asks for. Unlike streets
	 * this really is a page size: there are 302393 of them (measured 2026-08-15), so they
	 * arrive over 31 requests.
	 *
	 * <p>Same 10000 as {@link #COUNT}, and measured to be the right order of magnitude
	 * rather than assumed: one page is 10.5&nbsp;MB and takes 8-12&nbsp;s (three live
	 * samples, 2026-08-15). A smaller page would multiply the per-request overhead across a
	 * fetch that is already minutes long; a larger one buys little and grows the amount of
	 * work a single timeout throws away.
	 */
	private static final int ADDRESS_PAGE_SIZE = 10_000;

	/**
	 * Without this, every {@code gages:Hauskoordinaten} feature also carries its boundary
	 * polygon, and the whole extract goes from roughly 318&nbsp;MB to roughly 2.1&nbsp;GB.
	 * Measured 2026-08-15: 1000 addresses are 1.05&nbsp;MB in 890&nbsp;ms with it and
	 * 6.99&nbsp;MB in 3335&nbsp;ms without. It is what makes this fetch feasible at all,
	 * not an optimisation.
	 *
	 * <p>The service still returns {@code iso19112:locationType} on top of the two
	 * properties named here -- a WFS is free to include what its schema makes mandatory --
	 * which costs a few hundred bytes per feature and nothing else; {@link PlaceGmlReader}
	 * ignores it.
	 */
	private static final String ADDRESS_PROPERTIES = "iso19112:geographicIdentifier,gages:position";

	private static final String HAUSKOORDINATEN = "gages:Hauskoordinaten";

	/**
	 * Hard stop for the paging loop, so a service that never runs out of pages cannot make
	 * a refresh run forever. Roughly triple the 302393 addresses Hamburg has today -- far
	 * enough away that ordinary growth never reaches it, close enough that a runaway loop
	 * ends in minutes rather than never.
	 */
	private static final int MAX_ADDRESSES = 1_000_000;

	private final RestClient restClient;
	private final PlaceGmlReader gmlReader;

	HamburgPlaceFetcher(RestClient hamburgWfsRestClient, PlaceGmlReader gmlReader) {
		this.restClient = hamburgWfsRestClient;
		this.gmlReader = gmlReader;
	}

	List<ParsedPlace> fetchStrassen() {
		return exchange(streetsOrDistrictsUri("dog:Strassen"), "dog:Strassen", gmlReader::readStrassen);
	}

	List<ParsedPlace> fetchOrtsteile() {
		return exchange(streetsOrDistrictsUri("dog:Ortsteile"), "dog:Ortsteile", gmlReader::readOrtsteile);
	}

	/**
	 * How many house numbers Hamburg has on file, via a {@code RESULTTYPE=hits} request that
	 * returns an empty FeatureCollection of a few hundred bytes.
	 *
	 * <p>A request of its own rather than reading the first page's own header: a paged
	 * request that carries {@code PROPERTYNAME} answers {@code numberMatched="unknown"}
	 * (measured on all three live samples), so the count has to be asked for separately or
	 * not at all.
	 *
	 * @return the total, or {@code -1} if the service did not name one -- see
	 *         {@link PlaceGmlReader#readNumberMatched}
	 */
	long countHauskoordinaten() {
		URI uri = baseRequest(HAUSKOORDINATEN)
				.queryParam("RESULTTYPE", "hits")
				.build()
				.toUri();
		return exchange(uri, HAUSKOORDINATEN, gmlReader::readNumberMatched);
	}

	/**
	 * Fetches every house number Hamburg has, one page at a time, handing each page to
	 * {@code onPage} as it arrives rather than returning them all at once -- what lets the
	 * caller report progress during a fetch that takes minutes, and keeps the page size a
	 * detail of this class.
	 *
	 * <p>Paging stops at {@code expectedTotal} (from {@link #countHauskoordinaten}) and,
	 * independently of it, as soon as a page comes back empty. Both are needed: the count
	 * is taken before the first page and could in principle grow between the two, and a
	 * count of {@code -1} means the service named none at all.
	 *
	 * @return the number of pages actually fetched
	 */
	int fetchHauskoordinaten(long expectedTotal, Consumer<List<ParsedPlace>> onPage) {
		int pages = 0;
		for (int startIndex = 0; startIndex < MAX_ADDRESSES; startIndex += ADDRESS_PAGE_SIZE) {
			if (expectedTotal >= 0 && startIndex >= expectedTotal) {
				return pages;
			}
			List<ParsedPlace> page = fetchHauskoordinatenPage(startIndex);
			pages++;
			if (page.isEmpty()) {
				return pages;
			}
			onPage.accept(page);
		}
		throw new PlaceRefreshException("Der Abzug der Hamburger Hauskoordinaten hat " + MAX_ADDRESSES
				+ " Adressen überschritten und wurde abgebrochen.");
	}

	private List<ParsedPlace> fetchHauskoordinatenPage(int startIndex) {
		URI uri = baseRequest(HAUSKOORDINATEN)
				.queryParam("COUNT", ADDRESS_PAGE_SIZE)
				.queryParam("STARTINDEX", startIndex)
				.queryParam("PROPERTYNAME", ADDRESS_PROPERTIES)
				.build()
				.toUri();
		return exchange(uri, HAUSKOORDINATEN, gmlReader::readHauskoordinaten);
	}

	private static URI streetsOrDistrictsUri(String typeName) {
		return baseRequest(typeName).queryParam("COUNT", COUNT).build().toUri();
	}

	private static UriComponentsBuilder baseRequest(String typeName) {
		return UriComponentsBuilder.fromUriString(BASE_URL)
				.queryParam("SERVICE", "WFS")
				.queryParam("VERSION", "2.0.0")
				.queryParam("REQUEST", "GetFeature")
				.queryParam("TYPENAMES", typeName);
	}

	private <T> T exchange(URI uri, String typeName, Function<InputStream, T> read) {
		return restClient.get().uri(uri).exchange((request, response) -> {
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new PlaceRefreshException(
						"Der WFS-Dienst von Hamburg antwortete mit " + response.getStatusCode() + " für " + typeName);
			}
			try (InputStream body = response.getBody()) {
				return read.apply(body);
			}
		});
	}
}
