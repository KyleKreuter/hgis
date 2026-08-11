package de.kreuter.hgis.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The export endpoint against a real PostGIS table.
 *
 * <p>The fixture is a projected layer (EPSG:25832) with one column of every kind the
 * import chain produces and a NULL in each of them, because the two ways an export goes
 * quietly wrong are a coordinate that never left the storage CRS and an attribute whose
 * type was flattened into a string on the way out. Both look like a perfectly valid file.
 *
 * <p>One polygon is deliberately wound clockwise: RFC 7946 fixes the orientation of
 * polygon rings, PostGIS stores whatever the source had, and a reader that honours the
 * rule reads an unturned exterior ring as a hole.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeoJsonExportTest {

	/** Quote, path separator, umlaut and a CRLF -- everything a header must not inherit. */
	private static final String LAYER_NAME = "Hamburger \"Stadtteile\" / ä\r\n";

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository fieldRepository;

	@Autowired
	private GeoJsonExportService service;

	private Project project;
	private Layer layer;
	private String tableName;
	private List<Long> fids;

	@BeforeAll
	void createLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Export-Test " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    strasse   text,
				    einwohner bigint,
				    hoehe     double precision,
				    denkmal   boolean,
				    stichtag  date,
				    geom      geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();

		fids = List.of(
				insert(table, "Alsterufer", 1200L, 12.5, true, "2024-03-01",
						"ST_Multi(ST_MakeEnvelope(550000, 5930000, 550050, 5930100, 25832))"),
				insert(table, "Große Elbstraße", null, null, false, null,
						"ST_Multi(ST_MakeEnvelope(550100, 5930000, 550150, 5930100, 25832))"),
				// Ring order x0y0 -> x0y1 -> x1y1 -> x1y0: clockwise, the wrong way round
				// for RFC 7946 and exactly what a shapefile delivers.
				insert(table, null, 7L, -3.25, null, "1999-12-31",
						"ST_Multi(ST_GeomFromText('POLYGON((550200 5930000, 550200 5930100, "
								+ "550250 5930100, 550250 5930000, 550200 5930000))', 25832))"));

		Layer newLayer = new Layer(layerId, project, LAYER_NAME, tableName, "MULTIPOLYGON", 25832);
		newLayer.setFeatureCount(fids.size());
		layer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(layer, "Straße", "strasse", "text", 0));
		fieldRepository.saveAndFlush(new LayerField(layer, "Einwohner", "einwohner", "bigint", 1));
		fieldRepository.saveAndFlush(
				new LayerField(layer, "Höhe ü. NN", "hoehe", "double precision", 2));
		fieldRepository.saveAndFlush(new LayerField(layer, "Denkmal?", "denkmal", "boolean", 3));
		fieldRepository.saveAndFlush(new LayerField(layer, "Stichtag", "stichtag", "date", 4));
	}

	@AfterAll
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.deleteById(layer.getId());
		projectRepository.deleteById(project.getId());
	}

	private long insert(String table, String strasse, Long einwohner, Double hoehe,
			Boolean denkmal, String stichtag, String geometry) {
		return jdbc.sql("INSERT INTO " + table
						+ " (strasse, einwohner, hoehe, denkmal, stichtag, geom)"
						+ " VALUES (:strasse, :einwohner, :hoehe, :denkmal, CAST(:stichtag AS date), "
						+ geometry + ") RETURNING fid")
				.param("strasse", strasse)
				.param("einwohner", einwohner)
				.param("hoehe", hoehe)
				.param("denkmal", denkmal)
				.param("stichtag", stichtag)
				.query(Long.class)
				.single();
	}

	// --- full export ---------------------------------------------------------------

	@Test
	@DisplayName("without a selection the whole layer is exported")
	void exportsEveryFeature() throws Exception {
		JsonNode collection = exportAsJson(get(url()));

		assertThat(collection.get("type").asString()).isEqualTo("FeatureCollection");
		assertThat(collection.get("features")).hasSize(fids.size());
		assertThat(fidsOf(collection)).containsExactlyElementsOf(fids);
		assertThat(collection.get("features").get(0).get("type").asString()).isEqualTo("Feature");
	}

	@Test
	@DisplayName("the collection carries no crs member: RFC 7946 has none")
	void omitsTheObsoleteCrsMember() throws Exception {
		assertThat(exportAsJson(get(url())).has("crs")).isFalse();
	}

	@Test
	@DisplayName("the layer name travels as a foreign member, escaped")
	void namesTheCollection() throws Exception {
		assertThat(exportAsJson(get(url())).get("name").asString()).isEqualTo(LAYER_NAME);
	}

	// --- geometry ------------------------------------------------------------------

	@Test
	@DisplayName("geometry is reprojected from the layer CRS to EPSG:4326")
	void writesGeometryInWgs84() throws Exception {
		JsonNode geometry = exportAsJson(get(url())).get("features").get(0).get("geometry");

		assertThat(geometry.get("type").asString()).isEqualTo("MultiPolygon");

		JsonNode corner = geometry.get("coordinates").get(0).get(0).get(0);
		// 550000 / 5930000 in EPSG:25832 is Hamburg. Untransformed metres would be six
		// and seven digits here, which is the failure this pins down.
		assertThat(corner.get(0).asDouble()).as("longitude").isBetween(9.0, 11.0);
		assertThat(corner.get(1).asDouble()).as("latitude").isBetween(53.0, 54.0);
	}

	@Test
	@DisplayName("a clockwise polygon comes out counter-clockwise, as RFC 7946 requires")
	void orientsExteriorRingsCounterClockwise() throws Exception {
		JsonNode features = exportAsJson(get(url())).get("features");

		for (JsonNode feature : features) {
			JsonNode ring = feature.get("geometry").get("coordinates").get(0).get(0);
			assertThat(signedArea(ring))
					.as("exterior ring of feature %s", feature.get("id"))
					.isGreaterThan(0);
		}
	}

	// --- attributes ------------------------------------------------------------------

	@Test
	@DisplayName("attributes keep the names the UI shows, plus the row id as fid")
	void usesSourceNamesAsPropertyKeys() throws Exception {
		JsonNode properties = exportAsJson(get(url())).get("features").get(0).get("properties");

		assertThat(properties.propertyNames())
				.containsExactly("fid", "Straße", "Einwohner", "Höhe ü. NN", "Denkmal?", "Stichtag");
		assertThat(properties.get("fid").asLong()).isEqualTo(fids.get(0));
	}

	@Test
	@DisplayName("values keep their type; NULL stays null and does not become an empty string")
	void preservesTypesAndNulls() throws Exception {
		JsonNode features = exportAsJson(get(url())).get("features");

		JsonNode first = features.get(0).get("properties");
		assertThat(first.get("Straße").asString()).isEqualTo("Alsterufer");
		assertThat(first.get("Einwohner").isIntegralNumber()).isTrue();
		assertThat(first.get("Einwohner").asLong()).isEqualTo(1200L);
		assertThat(first.get("Höhe ü. NN").isFloatingPointNumber()).isTrue();
		assertThat(first.get("Höhe ü. NN").asDouble()).isEqualTo(12.5);
		assertThat(first.get("Denkmal?").isBoolean()).isTrue();
		assertThat(first.get("Denkmal?").asBoolean()).isTrue();
		assertThat(first.get("Stichtag").asString())
				.as("a date is an ISO string, not a timestamp and not a number")
				.isEqualTo("2024-03-01");

		JsonNode second = features.get(1).get("properties");
		assertThat(second.get("Straße").asString())
				.as("umlauts survive as UTF-8")
				.isEqualTo("Große Elbstraße");
		assertThat(second.get("Einwohner").isNull()).isTrue();
		assertThat(second.get("Höhe ü. NN").isNull()).isTrue();
		assertThat(second.get("Stichtag").isNull()).isTrue();

		JsonNode third = features.get(2).get("properties");
		assertThat(third.get("Straße").isNull()).isTrue();
		assertThat(third.get("Denkmal?").isNull()).isTrue();
		assertThat(third.get("Höhe ü. NN").asDouble()).isEqualTo(-3.25);
	}

	// --- selection ---------------------------------------------------------------

	@Test
	void exportsOnlyTheSelectedFeatures() throws Exception {
		JsonNode collection = exportAsJson(
				get(url()).param("fids", fids.get(0) + "," + fids.get(2)));

		assertThat(fidsOf(collection)).containsExactly(fids.get(0), fids.get(2));
	}

	@Test
	@DisplayName("a fid that no longer exists narrows the result instead of failing it")
	void ignoresUnknownFids() throws Exception {
		JsonNode collection = exportAsJson(get(url()).param("fids", fids.get(1) + ",999999999"));

		assertThat(fidsOf(collection)).containsExactly(fids.get(1));
	}

	@Test
	void doesNotRepeatADuplicatedFid() throws Exception {
		JsonNode collection = exportAsJson(
				get(url()).param("fids", fids.get(1) + "," + fids.get(1)));

		assertThat(fidsOf(collection)).containsExactly(fids.get(1));
	}

	@Test
	@DisplayName("an explicitly empty selection is an empty collection, never the whole layer")
	void exportsNothingForAnEmptySelection() throws Exception {
		JsonNode collection = exportAsJson(get(url()).param("fids", ""));

		assertThat(collection.get("type").asString()).isEqualTo("FeatureCollection");
		assertThat(collection.get("features")).isEmpty();
	}

	// --- POST --------------------------------------------------------------------

	@Test
	@DisplayName("POST takes the same selection for lists too long for a URL")
	void postExportsTheSelection() throws Exception {
		JsonNode collection = exportAsJson(post(url())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fids\":[" + fids.get(2) + "," + fids.get(0) + "]}"));

		assertThat(fidsOf(collection)).containsExactly(fids.get(0), fids.get(2));
	}

	@Test
	void postWithoutFidsExportsTheWholeLayer() throws Exception {
		JsonNode collection = exportAsJson(post(url())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"));

		assertThat(fidsOf(collection)).containsExactlyElementsOf(fids);
	}

	@Test
	void postWithAnEmptyArrayExportsNothing() throws Exception {
		JsonNode collection = exportAsJson(post(url())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fids\":[]}"));

		assertThat(collection.get("features")).isEmpty();
	}

	// --- transport ---------------------------------------------------------------

	@Test
	@DisplayName("the response is a geo+json download with a safe file name")
	void servesADownload() throws Exception {
		MockHttpServletResponse response = export(get(url()));

		assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/geo+json");
		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");

		String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
		assertThat(disposition).startsWith("attachment;");
		assertThat(disposition)
				.as("the quote, the slash and the CRLF of the layer name are all gone")
				.contains("filename=\"Hamburger_Stadtteile_ae.geojson\"")
				.doesNotContain("\r")
				.doesNotContain("\n");
		assertThat(disposition)
				.as("the readable name survives percent-encoded")
				.contains("filename*=UTF-8''Hamburger%20%22Stadtteile%22%20_%20%C3%A4.geojson");
	}

	// --- errors ---------------------------------------------------------------

	@Test
	void reportsAnUnknownLayer() throws Exception {
		MockHttpServletResponse response = export(
				get("/api/layers/{layerId}/export.geojson", UUID.randomUUID()));

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentType()).startsWith("application/problem+json");
	}

	@Test
	void reportsAnUnknownLayerOnPostAsWell() throws Exception {
		MockHttpServletResponse response = export(
				post("/api/layers/{layerId}/export.geojson", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fids\":[1]}"));

		assertThat(response.getStatus()).isEqualTo(404);
	}

	@Test
	@DisplayName("a fid that is not a number is rejected before it reaches a query")
	void rejectsANonNumericFid() throws Exception {
		MockHttpServletResponse response = export(
				get(url()).param("fids", "1) OR true--"));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentType()).startsWith("application/problem+json");
	}

	@Test
	@DisplayName("an injected statement is a 400 and the layer table is still there")
	void survivesAnInjectionAttempt() throws Exception {
		MockHttpServletResponse response =
				export(get(url()).param("fids", "1; DROP TABLE gis_meta.layer"));

		// The status matters as much as the surviving table: a 200 here would mean the
		// string reached a query and merely failed to do anything, which is a different
		// and far less comfortable kind of safe.
		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentType()).startsWith("application/problem+json");

		assertThat(layerRepository.existsById(layer.getId())).isTrue();
		assertThat(exportAsJson(get(url())).get("features")).hasSize(fids.size());
	}

	// --- request size ---------------------------------------------------------------

	@Test
	@DisplayName("a body past the limit is refused before it is parsed")
	void rejectsAnOversizedBody() throws Exception {
		// Syntactically fine and semantically harmless -- an unknown member Jackson would
		// ignore. Only its size is wrong, so a 413 can only come from the filter in front
		// of the parser, which is the point.
		String padding = "A".repeat(ExportBodyLimitFilter.MAX_BODY_BYTES);
		MockHttpServletResponse response = export(post(url())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fids\":[1],\"padding\":\"" + padding + "\"}"));

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentType()).startsWith("application/problem+json");
	}

	@Test
	@DisplayName("a selection past MAX_FIDS is a 400, one limit behind the other")
	void rejectsTooManyFids() throws Exception {
		StringBuilder body = new StringBuilder("{\"fids\":[0");
		for (int i = 1; i <= FidSelection.MAX_FIDS; i++) {
			body.append(',').append(i);
		}
		body.append("]}");

		// Roughly 700 KB: comfortably inside the byte limit, so this request gets as far
		// as FidSelection and is stopped by the count rather than by the size.
		assertThat(body.length()).isLessThan(ExportBodyLimitFilter.MAX_BODY_BYTES);

		MockHttpServletResponse response = export(post(url())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body.toString()));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains(String.valueOf(FidSelection.MAX_FIDS));
	}

	// --- a client that stops reading -------------------------------------------------

	@Test
	@DisplayName("a broken pipe ends the export quietly, not as a server error")
	void survivesAClientThatStopsReading() {
		GeoJsonExportService.Export export =
				service.prepare(layer.getId(), FidSelection.wholeLayer());

		// A cancelled download, seen from the inside: the socket refuses the moment the
		// buffer is flushed. Nothing may come back out of write() -- the request is over
		// and there is nobody left to tell.
		assertThatNoException()
				.isThrownBy(() -> service.write(export, new OutputStream() {
					@Override
					public void write(int b) throws IOException {
						throw new IOException("Broken pipe");
					}

					@Override
					public void write(byte[] buffer, int offset, int length) throws IOException {
						throw new IOException("Broken pipe");
					}
				}));
	}

	// --- helpers ---------------------------------------------------------------

	private String url() {
		return "/api/layers/" + layer.getId() + "/export.geojson";
	}

	/**
	 * The body is written by {@code StreamingResponseBody} on the async thread, into the
	 * response of the first dispatch -- so the async cycle has to be completed before
	 * anything is read out of it.
	 */
	private MockHttpServletResponse export(RequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request).andReturn();
		if (result.getRequest().isAsyncStarted()) {
			result.getAsyncResult();
			mockMvc.perform(asyncDispatch(result));
		}
		return result.getResponse();
	}

	private JsonNode exportAsJson(RequestBuilder request) throws Exception {
		MockHttpServletResponse response = export(request);
		assertThat(response.getStatus()).isEqualTo(200);
		// Explicitly UTF-8: the media type carries no charset, and the mock response
		// would otherwise decode the umlauts as ISO-8859-1.
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static List<Long> fidsOf(JsonNode collection) {
		return collection.get("features").valueStream()
				.map(feature -> feature.get("id").asLong())
				.toList();
	}

	/** Shoelace formula: positive for a counter-clockwise ring. */
	private static double signedArea(JsonNode ring) {
		double sum = 0;
		for (int i = 0; i < ring.size() - 1; i++) {
			JsonNode from = ring.get(i);
			JsonNode to = ring.get(i + 1);
			sum += from.get(0).asDouble() * to.get(1).asDouble()
					- to.get(0).asDouble() * from.get(1).asDouble();
		}
		return sum / 2;
	}
}
