package de.kreuter.hgis.geoportal;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.ingest.reader.QueryablesSchema;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The read-only half of talking to one collection of Hamburg's OGC API Features: what
 * CONTRACT.md 11.4 (dataset detail) and 11.5 (count with a bbox) need before anything is
 * imported. {@link de.kreuter.hgis.ingest.reader.OgcFeaturesSourceReader} does the actual
 * importing and, deliberately, does not share this class -- {@code ingest.reader} must not
 * depend on {@code geoportal} (that dependency only ever runs the other way), so its own
 * small, separate fetch of the same documents lives there instead.
 */
@Component
class OgcFeaturesClient {

	private static final Pattern EPSG_CODE = Pattern.compile("EPSG/0/(\\d+)");

	private final RestClient restClient;
	private final ObjectMapper mapper = new ObjectMapper();

	OgcFeaturesClient(RestClient geoportalRestClient) {
		this.restClient = geoportalRestClient;
	}

	/**
	 * @param itemCount   the collection's own count, independent of any filter
	 * @param bboxWgs84   {@code [minLng, minLat, maxLng, maxLat]}, or null when the
	 *                    collection's {@code extent.spatial} carries none
	 * @param storageSrid the collection's {@code storageCrs}, parsed to its EPSG code, or
	 *                    null when the collection does not name an EPSG-coded storage CRS
	 *                    (CONTRACT.md 11.4: {@code storageSrid})
	 */
	record CollectionInfo(Long itemCount, double[] bboxWgs84, Integer storageSrid) {
	}

	/**
	 * CONTRACT.md 11.4's {@code description}. Not carried by the collection endpoint at
	 * all -- measured live against several collections, none returned the key -- but the
	 * API's own landing page, one level up, does. For an API with more than one collection
	 * this describes the whole API rather than the one collection picked, the same
	 * approximation {@link de.kreuter.hgis.geoportal.CatalogLoader} already makes when it
	 * binds a dataset row to the first collection it finds.
	 *
	 * @return null when the landing page carries none
	 */
	String fetchApiDescription(String apiUrl) {
		URI uri = UriComponentsBuilder.fromUriString(apiUrl)
				.queryParam("f", "json")
				.build()
				.encode()
				.toUri();
		return getJson(uri).path("description").asString(null);
	}

	CollectionInfo fetchCollection(String apiUrl, String collection) {
		URI uri = UriComponentsBuilder.fromUriString(apiUrl)
				.pathSegment("collections", collection)
				.queryParam("f", "json")
				.build()
				.encode()
				.toUri();
		JsonNode body = getJson(uri);
		Long itemCount = body.path("itemCount").isNumber() ? body.path("itemCount").asLong() : null;
		Integer storageSrid = parseEpsgCode(body.path("storageCrs").asString(null));
		return new CollectionInfo(itemCount, extentBbox(body.path("extent").path("spatial")), storageSrid);
	}

	private static Integer parseEpsgCode(String crsUri) {
		if (crsUri == null) {
			return null;
		}
		Matcher matcher = EPSG_CODE.matcher(crsUri);
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}

	List<QueryablesSchema.Field> fetchQueryables(String apiUrl, String collection, Map<String, String> germanLabels) {
		URI uri = UriComponentsBuilder.fromUriString(apiUrl)
				.pathSegment("collections", collection, "queryables")
				.queryParam("f", "json")
				.build()
				.encode()
				.toUri();
		return QueryablesSchema.parse(getJson(uri), germanLabels);
	}

	/**
	 * CONTRACT.md 11.5: how many features survive a bbox, without fetching any of them.
	 * {@code limit=1} keeps the request itself as cheap as the question it answers --
	 * {@code numberMatched} is present in the response regardless of {@code limit} (measured
	 * live against the tree cadastre: {@code bbox} alone, no {@code limit}, already returns it).
	 */
	long countMatching(String apiUrl, String collection, double[] bbox4326) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(apiUrl)
				.pathSegment("collections", collection, "items")
				.queryParam("f", "json")
				.queryParam("limit", 1);
		if (bbox4326 != null) {
			builder.queryParam("bbox", bbox4326[0] + "," + bbox4326[1] + "," + bbox4326[2] + "," + bbox4326[3]);
		}
		URI uri = builder.build().encode().toUri();
		return getJson(uri).path("numberMatched").asLong(0);
	}

	private static double[] extentBbox(JsonNode spatial) {
		JsonNode bboxArray = spatial.path("bbox");
		if (!bboxArray.isArray() || bboxArray.isEmpty()) {
			return null;
		}
		JsonNode first = bboxArray.get(0);
		if (!first.isArray() || first.size() < 4) {
			return null;
		}
		return new double[] { first.get(0).asDouble(), first.get(1).asDouble(), first.get(2).asDouble(),
				first.get(3).asDouble() };
	}

	private JsonNode getJson(URI uri) {
		return restClient.get().uri(uri).exchange((request, response) -> {
			if (response.getStatusCode().is4xxClientError()) {
				throw new BadRequestException("Geoportal-Datensatz nicht erreichbar (" + response.getStatusCode() + ")");
			}
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new GeoportalUnavailableException(
						"Geoportal antwortete mit " + response.getStatusCode() + " auf " + uri);
			}
			try (InputStream body = response.getBody()) {
				return mapper.readTree(body);
			}
		});
	}
}
