package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Exercises {@link OgcFeaturesSourceReader} against canned responses shaped like the real
 * ones (tree cadastre, {@code strassenbaumkataster_hh}, measured live -- see the phase 23
 * backend report). No test here touches the network: {@link MockRestServiceServer} answers
 * every request, so this suite never depends on {@code api.hamburg.de} being reachable.
 */
class OgcFeaturesSourceReaderTest {

	private static final String API_URL = "https://api.hamburg.de/datasets/v1/strassenbaumkataster";
	private static final String COLLECTION = "strassenbaumkataster_hh";

	private static final String COLLECTION_INFO = """
			{"title":"Straßenbaumkataster Hamburg","id":"strassenbaumkataster_hh",
			 "extent":{"spatial":{"bbox":[[9.73,53.39,10.32,53.72]],"crs":"http://www.opengis.net/def/crs/OGC/1.3/CRS84"}},
			 "crs":["http://www.opengis.net/def/crs/OGC/1.3/CRS84","http://www.opengis.net/def/crs/EPSG/0/25832",
			        "http://www.opengis.net/def/crs/EPSG/0/4326","http://www.opengis.net/def/crs/EPSG/0/3857"],
			 "storageCrs":"http://www.opengis.net/def/crs/EPSG/0/25832","itemCount":229876}
			""";

	private static final String COLLECTION_INFO_NO_25832 = """
			{"title":"Ohne 25832","id":"strassenbaumkataster_hh",
			 "extent":{"spatial":{"bbox":[[9.73,53.39,10.32,53.72]],"crs":"http://www.opengis.net/def/crs/OGC/1.3/CRS84"}},
			 "crs":["http://www.opengis.net/def/crs/OGC/1.3/CRS84"],
			 "itemCount":2}
			""";

	private static final String QUERYABLES = """
			{"properties": {
			  "gid": {"title":"gid","type":"integer","readOnly":true,"x-ogc-role":"id"},
			  "baumid": {"title":"BaumID","type":"integer"},
			  "gattung": {"title":"Gattung","type":"string","enum":["Abies / Tanne","Tilia / Linde"]},
			  "kronendurchmesser_z": {"title":"kronendurchmesser_z","type":"string"}
			}}
			""";

	private static final Map<String, String> GERMAN_LABELS =
			Map.of("gattung", "Gattung", "kronendurchmesser_z", "Kronendurchmesser");

	private static final String ITEMS_PAGE = """
			{"type":"FeatureCollection","numberReturned":2,"numberMatched":2,"features":[
			  {"type":"Feature","id":1,"geometry":{"type":"MultiPoint","coordinates":[[565000,5931000]]},
			   "properties":{"baumid":100,"gattung":"Tilia / Linde","kronendurchmesser_z":"5 m"}},
			  {"type":"Feature","id":2,"geometry":{"type":"MultiPoint","coordinates":[[565100,5931100]]},
			   "properties":{"baumid":101,"gattung":"Abies / Tanne","kronendurchmesser_z":null}}
			]}
			""";

	private record Harness(RestClient restClient, MockRestServiceServer server) {
	}

	private static Harness harness() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		return new Harness(builder.build(), server);
	}

	private static void expectCollectionInfo(MockRestServiceServer server, String body) {
		server.expect(requestTo(containsString("/collections/" + COLLECTION + "?")))
				.andExpect(method(HttpMethod.GET))
				.andExpect(queryParam("f", "json"))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private static void expectQueryables(MockRestServiceServer server) {
		server.expect(requestTo(containsString("/collections/" + COLLECTION + "/queryables")))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(QUERYABLES, MediaType.APPLICATION_JSON));
	}

	private static void expectItemsPage(MockRestServiceServer server, String contentCrs) {
		var responseBuilder = withSuccess(ITEMS_PAGE, MediaType.APPLICATION_JSON);
		if (contentCrs != null) {
			responseBuilder.headers(headersWithContentCrs(contentCrs));
		}
		server.expect(requestTo(containsString("/collections/" + COLLECTION + "/items")))
				.andExpect(method(HttpMethod.GET))
				.andExpect(queryParam("limit", "10000"))
				.andExpect(queryParam("offset", "0"))
				.andRespond(responseBuilder);
	}

	private static HttpHeaders headersWithContentCrs(String value) {
		HttpHeaders headers = new HttpHeaders();
		headers.add("Content-Crs", value);
		return headers;
	}

	@Test
	@DisplayName("believes the response's Content-Crs header, not the request it sent (CONTRACT.md 11.8)")
	void trustsContentCrsHeaderOverTheRequestedCrs() {
		Harness h = harness();
		expectCollectionInfo(h.server(), COLLECTION_INFO);
		expectQueryables(h.server());
		expectItemsPage(h.server(), "<http://www.opengis.net/def/crs/EPSG/0/25832>");

		try (SourceReader reader = new OgcFeaturesSourceReader(h.restClient(), API_URL, COLLECTION, null, null, GERMAN_LABELS)) {
			SourceSchema schema = reader.schema();
			assertThat(schema.sourceSrid()).isEqualTo(25832);
			assertThat(schema.crsConfidence()).isEqualTo(SourceSchema.CrsConfidence.DECLARED);
			assertThat(schema.featureCount()).isEqualTo(2L);
			assertThat(schema.geometryType()).isEqualTo(GeometryType.MULTIPOINT);
		}
		h.server().verify();
	}

	@Test
	@DisplayName("a missing Content-Crs header means CRS84, i.e. EPSG:4326")
	void missingContentCrsHeaderMeansCrs84() {
		Harness h = harness();
		expectCollectionInfo(h.server(), COLLECTION_INFO_NO_25832);
		expectQueryables(h.server());
		expectItemsPage(h.server(), null);

		try (SourceReader reader = new OgcFeaturesSourceReader(h.restClient(), API_URL, COLLECTION, null, null, GERMAN_LABELS)) {
			assertThat(reader.schema().sourceSrid()).isEqualTo(4326);
		}
	}

	@Test
	@DisplayName("field titles resolve per CONTRACT.md 11.4, and column basis stays the technical name (decision E1)")
	void resolvesTitlesAndKeepsTechnicalNamesForColumns() {
		Harness h = harness();
		expectCollectionInfo(h.server(), COLLECTION_INFO);
		expectQueryables(h.server());
		expectItemsPage(h.server(), "<http://www.opengis.net/def/crs/EPSG/0/25832>");

		OgcFeaturesSourceReader reader =
				new OgcFeaturesSourceReader(h.restClient(), API_URL, COLLECTION, null, null, GERMAN_LABELS);
		try (reader) {
			assertThat(reader.schema().fields()).extracting(f -> f.name())
					.containsExactly("gid", "BaumID", "Gattung", "Kronendurchmesser");
			assertThat(reader.columnNameBasis()).containsExactly("gid", "baumid", "gattung", "kronendurchmesser_z");
			assertThat(reader.idFieldIndex()).isZero();
		}
	}

	@Test
	@DisplayName("the id-role field is read from the GeoJSON Feature's own id, never from properties")
	void idFieldComesFromTopLevelFeatureId() {
		Harness h = harness();
		expectCollectionInfo(h.server(), COLLECTION_INFO);
		expectQueryables(h.server());
		expectItemsPage(h.server(), "<http://www.opengis.net/def/crs/EPSG/0/25832>");

		try (SourceReader reader = new OgcFeaturesSourceReader(h.restClient(), API_URL, COLLECTION, null, null, GERMAN_LABELS)) {
			List<SourceFeature> features;
			try (Stream<SourceFeature> stream = reader.features()) {
				features = stream.toList();
			}
			assertThat(features).hasSize(2);
			assertThat(features.get(0).attributes())
					.containsEntry("gid", 1L)
					.containsEntry("BaumID", 100L)
					.containsEntry("Gattung", "Tilia / Linde")
					.containsEntry("Kronendurchmesser", "5 m");
			assertThat(features.get(1).attributes()).containsEntry("gid", 2L).containsEntry("Kronendurchmesser", null);
			assertThat(reader.skippedCount()).isZero();
		}
	}

	@Test
	@DisplayName("a field selection keeps the id field even though it was never named (decision E6)")
	void fieldSelectionAlwaysKeepsTheIdField() {
		Harness h = harness();
		expectCollectionInfo(h.server(), COLLECTION_INFO);
		expectQueryables(h.server());
		expectItemsPage(h.server(), "<http://www.opengis.net/def/crs/EPSG/0/25832>");

		OgcFeaturesSourceReader reader = new OgcFeaturesSourceReader(h.restClient(), API_URL, COLLECTION, null,
				List.of("gattung"), GERMAN_LABELS);
		try (reader) {
			assertThat(reader.columnNameBasis()).containsExactly("gid", "gattung");
			assertThat(reader.idFieldIndex()).isZero();
		}
	}

	@Test
	@DisplayName("an unknown field name in the selection is a 400, not a silent drop (CONTRACT.md 11.6)")
	void unknownFieldSelectionIsRejected() {
		Harness h = harness();
		expectCollectionInfo(h.server(), COLLECTION_INFO);
		expectQueryables(h.server());

		assertThatThrownBy(() -> new OgcFeaturesSourceReader(h.restClient(), API_URL, COLLECTION, null,
				List.of("does_not_exist"), GERMAN_LABELS))
				.isInstanceOf(BadRequestException.class);
	}

	/**
	 * The tree cadastre's 229,876 objects page across 23 real requests (plan section 3.2)
	 * and that path must not be the one thing here left untested just because a literal
	 * 10,000-feature fixture would be unworkable. The package-private constructor makes
	 * the page size small enough to prove the same offset arithmetic against a handful of
	 * features instead: it advances correctly, every object arrives exactly once, and the
	 * reader stops asking once a short page says there is nothing left.
	 */
	@Test
	@DisplayName("blätterte über mehrere Seiten: Versatz stimmt, alle Objekte kommen an, keines doppelt")
	void pagesAcrossMultiplePagesWithoutGapsOrDuplicates() {
		Harness h = harness();
		expectCollectionInfo(h.server(), COLLECTION_INFO);
		expectQueryables(h.server());

		int pageSize = 3;
		expectItemsPageAt(h.server(), 0, pageSize, featurePage(1, 3));
		expectItemsPageAt(h.server(), 3, pageSize, featurePage(4, 3));
		expectItemsPageAt(h.server(), 6, pageSize, featurePage(7, 1)); // short page: paging stops here

		OgcFeaturesSourceReader reader = new OgcFeaturesSourceReader(
				h.restClient(), API_URL, COLLECTION, null, null, GERMAN_LABELS, pageSize);
		try (reader) {
			List<SourceFeature> features;
			try (Stream<SourceFeature> stream = reader.features()) {
				features = stream.toList();
			}
			assertThat(features).extracting(f -> f.attributes().get("gid"))
					.as("every id from 1 to 7, in order, none missing and none repeated")
					.containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
			assertThat(reader.skippedCount()).isZero();
		}
		h.server().verify();
	}

	/** One page of {@code count} synthetic MultiPoint features, ids starting at {@code firstId}. */
	private static String featurePage(int firstId, int count) {
		StringBuilder features = new StringBuilder();
		for (int i = 0; i < count; i++) {
			int id = firstId + i;
			if (i > 0) {
				features.append(',');
			}
			features.append("""
					{"type":"Feature","id":%d,"geometry":{"type":"MultiPoint","coordinates":[[%d,5931000]]},
					 "properties":{"baumid":%d,"gattung":"Tilia / Linde","kronendurchmesser_z":"5 m"}}
					""".formatted(id, 565000 + id, 100 + id));
		}
		return "{\"type\":\"FeatureCollection\",\"numberMatched\":7,\"features\":[" + features + "]}";
	}

	private static void expectItemsPageAt(MockRestServiceServer server, int offset, int limit, String body) {
		server.expect(requestTo(containsString("/collections/" + COLLECTION + "/items")))
				.andExpect(method(HttpMethod.GET))
				.andExpect(queryParam("limit", String.valueOf(limit)))
				.andExpect(queryParam("offset", String.valueOf(offset)))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON)
						.headers(headersWithContentCrs("<http://www.opengis.net/def/crs/EPSG/0/25832>")));
	}

	@Test
	@DisplayName("a bbox is sent as minLng,minLat,maxLng,maxLat and EPSG:25832 is requested when the collection offers it")
	void sendsBboxAndRequestsTheStorageCrsWhenOffered() {
		Harness h = harness();
		expectCollectionInfo(h.server(), COLLECTION_INFO);
		expectQueryables(h.server());
		h.server().expect(requestTo(containsString("/collections/" + COLLECTION + "/items")))
				.andExpect(method(HttpMethod.GET))
				.andExpect(queryParam("bbox", "9.99,53.55,10.0,53.56"))
				.andExpect(queryParam("crs", "http://www.opengis.net/def/crs/EPSG/0/25832"))
				.andRespond(withSuccess(ITEMS_PAGE, MediaType.APPLICATION_JSON)
						.headers(headersWithContentCrs("<http://www.opengis.net/def/crs/EPSG/0/25832>")));

		try (SourceReader reader = new OgcFeaturesSourceReader(h.restClient(), API_URL, COLLECTION,
				new double[] { 9.99, 53.55, 10.0, 53.56 }, null, GERMAN_LABELS)) {
			assertThat(reader.schema()).isNotNull();
		}
		h.server().verify();
	}
}
