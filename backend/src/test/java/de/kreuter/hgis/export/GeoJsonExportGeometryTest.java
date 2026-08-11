package de.kreuter.hgis.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every geometry type through the export, with the winding correction watching.
 *
 * <p>RFC 7946 section 3.1.6 prescribes an orientation for polygon rings, and PostGIS
 * offers {@code ST_ForcePolygonCCW} for it -- a function that is defined as
 * {@code ST_Reverse(ST_ForcePolygonCW(geom))} and therefore, applied to anything else,
 * turns a line round. A reversed route or river is not a difference a reader can detect:
 * the file stays valid, the geometry stays in place, only the direction is now the
 * opposite of what was imported. That is the failure this class exists for, and it is why
 * every line here is asserted coordinate by coordinate rather than by shape.
 *
 * <p>The project is EPSG:4326, so the exported coordinates are the stored ones and the
 * comparison can be exact. It is also the case the transform must not break: a layer that
 * is already in WGS 84 has nothing to reproject, and the export has to notice.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeoJsonExportGeometryTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** Clockwise, the wrong way round for RFC 7946 and what a shapefile delivers. */
	private static final String CW_RING =
			"(9.0 53.0, 9.0 54.0, 10.0 54.0, 10.0 53.0, 9.0 53.0)";

	/** Counter-clockwise, which is equally wrong for a hole. */
	private static final String CCW_HOLE =
			"(9.2 53.2, 9.4 53.2, 9.4 53.4, 9.2 53.4, 9.2 53.2)";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	private Project project;
	private Layer layer;
	private String tableName;

	/** One row per geometry type, in this order; the export is sorted by fid. */
	private static final List<String> GEOMETRIES = List.of(
			"LINESTRING(9.9 53.5, 10.0 53.6, 10.1 53.5)",
			"MULTILINESTRING((9.9 53.5, 10.0 53.6), (10.2 53.4, 10.3 53.3))",
			"POINT(9.95 53.55)",
			"MULTIPOINT((9.9 53.5), (10.1 53.7))",
			"POLYGON" + ring(CW_RING, CCW_HOLE),
			"MULTIPOLYGON(" + ring(CW_RING) + ")",
			"GEOMETRYCOLLECTION(LINESTRING(9.9 53.5, 10.0 53.6), POLYGON" + ring(CW_RING) + ")");

	private static String ring(String... rings) {
		return "(" + String.join(", ", rings) + ")";
	}

	@BeforeAll
	void createLayer() {
		// EPSG:4326 as the storage CRS, not only as the export CRS: the transform then
		// has nothing to do and every coordinate below can be compared literally.
		project = projectRepository.saveAndFlush(
				new Project("Geometrie-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);

		// A layer of no attributes at all, which is also the smallest properties object
		// the query has to build: nothing but the row id.
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(Geometry, 4326) NOT NULL
				)
				""".formatted(table)).update();

		for (String wkt : GEOMETRIES) {
			jdbc.sql("INSERT INTO " + table + " (geom) VALUES (ST_GeomFromText(:wkt, 4326))")
					.param("wkt", wkt)
					.update();
		}

		Layer newLayer = new Layer(layerId, project, "Geometrien", tableName, "GEOMETRY", 4326);
		newLayer.setFeatureCount(GEOMETRIES.size());
		layer = layerRepository.saveAndFlush(newLayer);
	}

	@AfterAll
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.deleteById(layer.getId());
		projectRepository.deleteById(project.getId());
	}

	// --- lines ---------------------------------------------------------------------

	@Test
	@DisplayName("a LineString keeps the direction it was stored with")
	void keepsLineStringDirection() throws Exception {
		JsonNode geometry = geometryOf(0);

		assertThat(geometry.get("type").asString()).isEqualTo("LineString");
		assertThat(coordinates(geometry.get("coordinates")))
				.as("start, middle and end in the stored order")
				.containsExactly(point(9.9, 53.5), point(10.0, 53.6), point(10.1, 53.5));
	}

	@Test
	@DisplayName("both parts of a MultiLineString keep their coordinate order")
	void keepsMultiLineStringDirection() throws Exception {
		JsonNode geometry = geometryOf(1);

		assertThat(geometry.get("type").asString()).isEqualTo("MultiLineString");
		assertThat(coordinates(geometry.get("coordinates").get(0)))
				.containsExactly(point(9.9, 53.5), point(10.0, 53.6));
		assertThat(coordinates(geometry.get("coordinates").get(1)))
				.containsExactly(point(10.2, 53.4), point(10.3, 53.3));
	}

	// --- points --------------------------------------------------------------------

	@Test
	void writesAPointUnchanged() throws Exception {
		JsonNode geometry = geometryOf(2);

		assertThat(geometry.get("type").asString()).isEqualTo("Point");
		assertThat(point(geometry.get("coordinates"))).isEqualTo(point(9.95, 53.55));
	}

	@Test
	void writesAMultiPointInOrder() throws Exception {
		JsonNode geometry = geometryOf(3);

		assertThat(geometry.get("type").asString()).isEqualTo("MultiPoint");
		assertThat(coordinates(geometry.get("coordinates")))
				.containsExactly(point(9.9, 53.5), point(10.1, 53.7));
	}

	// --- polygons ------------------------------------------------------------------

	@Test
	@DisplayName("a polygon with a hole comes out RFC 7946 compliant: shell CCW, hole CW")
	void orientsBothRingsOfAPolygon() throws Exception {
		JsonNode geometry = geometryOf(4);

		assertThat(geometry.get("type").asString()).isEqualTo("Polygon");
		assertThat(signedArea(geometry.get("coordinates").get(0)))
				.as("exterior ring, stored clockwise, must come out counter-clockwise")
				.isPositive();
		assertThat(signedArea(geometry.get("coordinates").get(1)))
				.as("the hole, stored counter-clockwise, must come out clockwise")
				.isNegative();
	}

	@Test
	void orientsEveryPartOfAMultiPolygon() throws Exception {
		JsonNode geometry = geometryOf(5);

		assertThat(geometry.get("type").asString()).isEqualTo("MultiPolygon");
		assertThat(signedArea(geometry.get("coordinates").get(0).get(0))).isPositive();
	}

	// --- collections ------------------------------------------------------------------

	@Test
	@DisplayName("a mixed collection is exported as stored, lines included")
	void leavesGeometryCollectionsAlone() throws Exception {
		JsonNode geometry = geometryOf(6);
		assertThat(geometry.get("type").asString()).isEqualTo("GeometryCollection");

		JsonNode line = geometry.get("geometries").get(0);
		assertThat(coordinates(line.get("coordinates")))
				.as("the line inside the collection may not be turned round either")
				.containsExactly(point(9.9, 53.5), point(10.0, 53.6));

		// The documented trade-off: PostGIS cannot reorient the polygonal members of a
		// collection without reversing the lines beside them, so the winding of a polygon
		// inside a collection is left as it was stored -- clockwise, here. RFC 7946 only
		// recommends the orientation; a line pointing the wrong way is data loss.
		JsonNode polygon = geometry.get("geometries").get(1);
		assertThat(signedArea(polygon.get("coordinates").get(0)))
				.as("still clockwise, exactly as it went in")
				.isNegative();
	}

	// --- the CRS itself ------------------------------------------------------------------

	@Test
	@DisplayName("a layer already in EPSG:4326 is exported without moving")
	void exportsAWgs84LayerUnchanged() throws Exception {
		JsonNode collection = exportAsJson();

		assertThat(collection.get("features")).hasSize(GEOMETRIES.size());
		// Reprojecting 4326 onto 4326 is a no-op, and anything else here -- a swapped
		// axis order, a transform through another CRS -- would show as a shifted digit.
		assertThat(point(geometryOf(collection, 2).get("coordinates")))
				.isEqualTo(point(9.95, 53.55));
	}

	@Test
	void exportsOnlyTheSelectedGeometry() throws Exception {
		JsonNode collection = exportAsJson(get(url()).param("fids", String.valueOf(fid(0))));

		assertThat(collection.get("features")).hasSize(1);
		assertThat(collection.get("features").get(0).get("geometry").get("type").asString())
				.isEqualTo("LineString");
	}

	// --- helpers ---------------------------------------------------------------

	private String url() {
		return "/api/layers/" + layer.getId() + "/export.geojson";
	}

	/** Row ids are generated, so the nth inserted geometry is the nth feature. */
	private long fid(int index) throws Exception {
		return exportAsJson().get("features").get(index).get("id").asLong();
	}

	private JsonNode geometryOf(int index) throws Exception {
		return geometryOf(exportAsJson(), index);
	}

	private static JsonNode geometryOf(JsonNode collection, int index) {
		return collection.get("features").get(index).get("geometry");
	}

	private JsonNode exportAsJson() throws Exception {
		return exportAsJson(get(url()));
	}

	private JsonNode exportAsJson(RequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request).andReturn();
		if (result.getRequest().isAsyncStarted()) {
			result.getAsyncResult();
			mockMvc.perform(asyncDispatch(result));
		}
		MockHttpServletResponse response = result.getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static List<List<Double>> coordinates(JsonNode array) {
		return array.valueStream().map(GeoJsonExportGeometryTest::point).toList();
	}

	private static List<Double> point(JsonNode node) {
		return List.of(node.get(0).asDouble(), node.get(1).asDouble());
	}

	private static List<Double> point(double longitude, double latitude) {
		return List.of(longitude, latitude);
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
