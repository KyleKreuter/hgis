package de.kreuter.hgis.places;

import de.kreuter.hgis.places.dto.PlaceDtos;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Queries Photon (CONTRACT.md "Photon (live)") and turns its GeoJSON answer into
 * {@link PlaceDtos.Result}s. Every failure -- a timeout, a connection refused, a non-2xx
 * status, a body that will not parse -- is caught here and turned into an empty list rather
 * than an exception: CONTRACT.md is explicit that a Photon outage must still answer 200
 * with whatever Hamburg found, "kein Fehler". Doing the swallowing at the source, rather
 * than in {@link PlaceSearchService}, means the service does not need to know which of its
 * two sources is allowed to fail silently and which is not.
 */
@Component
class PhotonClient {

	private static final Logger log = LoggerFactory.getLogger(PhotonClient.class);

	/**
	 * Real, live shape (measured against photon.komoot.io, 2026-08-15): {@code osm_key ==
	 * "highway"} is what actually distinguishes a street from everything else Photon
	 * returns. CONTRACT.md's field list names only {@code osm_value} -- but that alone is
	 * ambiguous ("residential" is both a common road class and a landuse value), and
	 * Photon's own {@code osm_key} is the standard, reliable OSM discriminator for "this is
	 * a way tagged as a road". Read here even though CONTRACT.md does not list it; see the
	 * handover report for this deviation and the live samples it is based on.
	 */
	private static final String HIGHWAY_KEY = "highway";

	private final RestClient restClient;
	private final PhotonProperties properties;
	private final ObjectMapper objectMapper;

	PhotonClient(RestClient photonRestClient, PhotonProperties properties, ObjectMapper objectMapper) {
		this.restClient = photonRestClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	/** @return up to {@code limit} hits, best match first, or an empty list if Photon is
	 *  disabled or did not answer usably within its time budget */
	List<PlaceDtos.Result> search(String q, int limit) {
		if (!properties.enabled()) {
			return List.of();
		}

		URI uri = UriComponentsBuilder.fromUriString(properties.url())
				.queryParam("q", q)
				.queryParam("limit", limit)
				.queryParam("lang", "de")
				.build()
				.toUri();

		try {
			return restClient.get().uri(uri).exchange((request, response) -> {
				if (!response.getStatusCode().is2xxSuccessful()) {
					log.warn("Photon antwortete mit {}", response.getStatusCode());
					return List.<PlaceDtos.Result>of();
				}
				try (InputStream body = response.getBody()) {
					return parse(body, limit);
				}
			});
		}
		catch (RuntimeException e) {
			// Covers ResourceAccessException (timeout, connection refused) and any other
			// failure exchange() or parse() can throw -- CONTRACT.md's "kein Fehler" applies
			// to all of them alike, not only to the network case.
			log.warn("Photon nicht erreichbar oder Antwort nicht verwertbar: {}", e.getMessage());
			return List.of();
		}
	}

	private List<PlaceDtos.Result> parse(InputStream body, int limit) throws IOException {
		JsonNode root = objectMapper.readTree(body);
		JsonNode features = root.path("features");
		if (!features.isArray()) {
			return List.of();
		}

		List<PlaceDtos.Result> results = new ArrayList<>();
		for (JsonNode feature : features) {
			if (results.size() >= limit) {
				break;
			}
			PlaceDtos.Result result = toResult(feature);
			if (result != null) {
				results.add(result);
			}
		}
		return results;
	}

	private static PlaceDtos.Result toResult(JsonNode feature) {
		JsonNode properties = feature.path("properties");
		String name = blankToNull(properties.path("name").asString(null));
		if (name == null) {
			// Photon returns unnamed address/house-number results too; without a name
			// there is nothing to show under CONTRACT.md's "name" field.
			return null;
		}

		JsonNode coordinates = feature.path("geometry").path("coordinates");
		if (!coordinates.isArray() || coordinates.size() != 2) {
			return null;
		}
		double lng = coordinates.get(0).asDouble();
		double lat = coordinates.get(1).asDouble();

		String kind = HIGHWAY_KEY.equals(properties.path("osm_key").asString(null)) ? "street" : "place";
		String context = context(properties);

		return new PlaceDtos.Result(name, context, lng, lat, "photon", kind);
	}

	/**
	 * {@code "<Ort>, <PLZ>"}, the same shape Hamburg's own context uses -- CONTRACT.md's
	 * worked example ({@code "Sudwalde, 27257"}) pairs city with postcode, not with country
	 * as its prose ("sonst Ort und Land") separately suggests; the worked example is
	 * followed here since it is also the more useful pairing -- a postcode narrows down a
	 * same-named street far better than "Deutschland" ever could. See the handover report.
	 */
	private static String context(JsonNode properties) {
		String city = blankToNull(properties.path("city").asString(null));
		String postcode = blankToNull(properties.path("postcode").asString(null));
		if (city != null && postcode != null) {
			return city + ", " + postcode;
		}
		return city != null ? city : postcode;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
