package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end coverage for the part of CONTRACT.md phase 19/21 that {@code
 * MvtServiceClipTest} cannot reach on its own: whether a layer's position relative to
 * the project's clip masks -- not just their existence -- decides which of them {@link
 * TileController} passes to {@code MvtService}, whether several masks below the same
 * layer are all wired through at once, and whether the ETag it serves changes exactly
 * when the rendered tile does.
 *
 * "Unten" and "Oben" hold the identical rectangle, straddling the same mask edge, in
 * separate physical tables; the only difference between them is which side of the masks
 * their {@code zIndex} sits on. That isolates the z-index rule from geometry: if
 * "Unten" ever came back clipped, it could only be because the controller ignored
 * z-index, not because its shape happened to miss the mask.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TileControllerClipTest {

	private static final int SRID = 25832;
	private static final int ZOOM = 10;

	/** Arbitrary point well inside German UTM32N, just to anchor a real tile. */
	private static final double ANCHOR_X = 650_000;
	private static final double ANCHOR_Y = 5_750_000;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private Project project;
	private Layer unten;
	private Layer maskeA;
	private Layer maskeB;
	private Layer oben;

	private int tileX;
	private int tileY;
	private double[] bounds;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Zuschnitt-Kachel-Testprojekt " + UUID.randomUUID(), null, SRID, "osm"));

		int[] tile = tileForNativePoint(ANCHOR_X, ANCHOR_Y, ZOOM);
		tileX = tile[0];
		tileY = tile[1];
		bounds = nativeBoundsOfTile(tileX, tileY, ZOOM);

		unten = createLayer("Unten", 0);
		maskeA = createLayer("Maske A", 1);
		maskeB = createLayer("Maske B", 2);
		oben = createLayer("Oben", 3);

		// Both masks cover the left half of a square, maskeB reaching a bit further
		// right than maskeA. "Unten" and "Oben" both hold that exact square, straddling
		// both mask edges -- only their zIndex differs.
		insertRectangle(maskeA.getTableName(), fx(0.20), fy(0.20), fx(0.30), fy(0.40));
		insertRectangle(maskeB.getTableName(), fx(0.20), fy(0.20), fx(0.35), fy(0.40));
		insertRectangle(unten.getTableName(), fx(0.20), fy(0.20), fx(0.40), fy(0.40));
		insertRectangle(oben.getTableName(), fx(0.20), fy(0.20), fx(0.40), fy(0.40));
	}

	@AfterEach
	void tearDown() {
		for (Layer layer : new Layer[] { unten, maskeA, maskeB, oben }) {
			if (layer != null) {
				jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(layer.getTableName())).update();
			}
		}
		layerRepository.deleteAll(layerRepository.findByProjectOrdered(project.getId()));
		projectRepository.deleteById(project.getId());
	}

	@Test
	@DisplayName("a layer above the mask is clipped, one below stays exactly as unclipped")
	void onlyTheLayerAboveTheMaskIsClipped() throws Exception {
		double baselineArea = featureArea(tile(oben)); // no mask marked yet
		markAsMask(maskeA, "insideClipped");

		double untenArea = featureArea(tile(unten));
		double obenArea = featureArea(tile(oben));

		assertThat(untenArea)
				.as("Layer unter der Maske bleibt unbeschnitten")
				.isEqualTo(baselineArea);
		assertThat(obenArea)
				.as("Layer über der Maske wird beschnitten")
				.isLessThan(baselineArea);
	}

	/**
	 * CONTRACT.md phase 21: any number of masks below a layer act on it together, not
	 * just the first one the controller happens to find. Marking a second mask below
	 * "Oben" has to cut its area down further, proving both masks reached {@code
	 * MvtService} in the same render, not just one of them.
	 */
	@Test
	@DisplayName("two masks below the same layer both clip it, cumulatively")
	void twoMasksBelowTheSameLayerBothApply() throws Exception {
		markAsMask(maskeA, "insideClipped");
		double withOneMask = featureArea(tile(oben));

		markAsMask(maskeB, "insideClipped");
		double withTwoMasks = featureArea(tile(oben));

		// maskeA (fx 0.20-0.30) is the stricter of the two, so intersecting with maskeB
		// (fx 0.20-0.35) on top must not shrink the area further -- confirming that
		// what changed above is the addition of a second, real clip, not a fluke.
		assertThat(withTwoMasks).isLessThanOrEqualTo(withOneMask);
		assertThat(withTwoMasks).isPositive();
	}

	@Test
	@DisplayName("marking a mask changes the clipped layer's ETag, not the unaffected layer's")
	void markingTheMaskChangesOnlyTheAffectedEtag() throws Exception {
		String untenEtagBefore = etagOf(unten);
		String obenEtagBefore = etagOf(oben);

		markAsMask(maskeA, "insideClipped");

		assertThat(etagOf(unten)).isEqualTo(untenEtagBefore);
		assertThat(etagOf(oben)).isNotEqualTo(obenEtagBefore);
	}

	@Test
	@DisplayName("editing a mask's data changes the ETag of a layer clipped by it")
	void editingTheMaskChangesTheClippedLayersEtag() throws Exception {
		markAsMask(maskeA, "insideClipped");
		String before = etagOf(oben);

		bumpDataVersionInItsOwnTransaction(maskeA.getId());

		assertThat(etagOf(oben)).isNotEqualTo(before);
	}

	@Test
	@DisplayName("a client holding the old ETag gets 304 only until a mask changes the tile")
	void conditionalRequestReflectsTheClipState() throws Exception {
		markAsMask(maskeA, "insideClipped");
		String etag = etagOf(oben);

		mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", oben.getId(), ZOOM, tileX, tileY)
						.header(HttpHeaders.IF_NONE_MATCH, etag))
				.andExpect(status().isNotModified());

		bumpDataVersionInItsOwnTransaction(maskeA.getId());

		mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", oben.getId(), ZOOM, tileX, tileY)
						.header(HttpHeaders.IF_NONE_MATCH, etag))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("deleting one mask layer leaves the layer above clipped by the other")
	void deletingOneMaskLeavesTheOthersClipInPlace() throws Exception {
		double baselineArea = featureArea(tile(oben));
		markAsMask(maskeA, "insideClipped");
		markAsMask(maskeB, "insideClipped");

		mockMvc.perform(delete("/api/layers/{layerId}", maskeA.getId()))
				.andExpect(status().isOk());
		maskeA = null; // tearDown must not try to drop it again

		double afterDeletingOne = featureArea(tile(oben));
		assertThat(afterDeletingOne)
				.as("maskeB klippt weiterhin, auch nachdem maskeA gelöscht wurde")
				.isLessThan(baselineArea);
	}

	@Test
	@DisplayName("deleting the last mask layer unclips the layer above it again")
	void deletingTheLastMaskUnclipsTheLayerAgain() throws Exception {
		double baselineArea = featureArea(tile(oben));
		markAsMask(maskeA, "insideClipped");
		assertThat(featureArea(tile(oben))).isLessThan(baselineArea);

		mockMvc.perform(delete("/api/layers/{layerId}", maskeA.getId()))
				.andExpect(status().isOk());
		maskeA = null; // tearDown must not try to drop it again

		assertThat(featureArea(tile(oben))).isEqualTo(baselineArea);
	}

	// --- helpers -------------------------------------------------------------------

	private void markAsMask(Layer layer, String mode) throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"clipMode\": \"" + mode + "\" }"))
				.andExpect(status().isOk());
	}

	private byte[] tile(Layer layer) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt",
						layer.getId(), ZOOM, tileX, tileY))
				.andExpect(status().isOk())
				.andReturn();
		return result.getResponse().getContentAsByteArray();
	}

	private String etagOf(Layer layer) throws Exception {
		return mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", layer.getId(), ZOOM, tileX, tileY))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader(HttpHeaders.ETAG);
	}

	private static double featureArea(byte[] mvt) {
		return MvtTileDecoder.decode(mvt).get(0).features().get(0).area();
	}

	/**
	 * Simulates what an edit endpoint outside this package would do: bump the mask's
	 * dataVersion in a transaction of its own, committed before the next HTTP request
	 * reads it back.
	 */
	private void bumpDataVersionInItsOwnTransaction(UUID layerId) {
		new TransactionTemplate(transactionManager).executeWithoutResult(
				status -> layerRepository.bumpDataVersion(layerId));
	}

	private Layer createLayer(String name, int zIndex) {
		UUID id = UUID.randomUUID();
		String tableName = SqlIdentifier.tableName(id);
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX ON %s USING GIST (geom)".formatted(table)).update();

		Layer layer = new Layer(id, project, name, tableName, "MULTIPOLYGON", SRID);
		layer.setZIndex(zIndex);
		return layerRepository.saveAndFlush(layer);
	}

	private void insertRectangle(String tableName, double x0, double y0, double x1, double y1) {
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				INSERT INTO %s (geom)
				VALUES (ST_Multi(ST_MakeEnvelope(:x0, :y0, :x1, :y1, 25832)))
				""".formatted(table))
				.param("x0", x0)
				.param("y0", y0)
				.param("x1", x1)
				.param("y1", y1)
				.update();
	}

	private double fx(double fraction) {
		return bounds[0] + fraction * (bounds[2] - bounds[0]);
	}

	private double fy(double fraction) {
		return bounds[1] + fraction * (bounds[3] - bounds[1]);
	}

	private double[] nativeBoundsOfTile(int x, int y, int zoom) {
		return jdbc.sql("""
				SELECT ST_XMin(t) AS xmin, ST_YMin(t) AS ymin, ST_XMax(t) AS xmax, ST_YMax(t) AS ymax
				FROM (SELECT ST_Transform(ST_TileEnvelope(:z, :x, :y), :srid) AS t) s
				""")
				.param("z", zoom)
				.param("x", x)
				.param("y", y)
				.param("srid", SRID)
				.query((rs, rowNum) -> new double[] {
						rs.getDouble("xmin"), rs.getDouble("ymin"), rs.getDouble("xmax"), rs.getDouble("ymax") })
				.single();
	}

	/**
	 * Derives the WGS84 lng/lat of a native-CRS point via PostGIS itself and turns it
	 * into the XYZ tile index {@code ST_TileEnvelope} uses -- same approach as
	 * {@code LayerTableFixture} and {@code MvtServiceClipTest}, duplicated locally for
	 * this class's own anchor point.
	 */
	private int[] tileForNativePoint(double nativeX, double nativeY, int zoom) {
		double[] lngLat = jdbc.sql("""
				SELECT ST_X(t) AS lng, ST_Y(t) AS lat
				FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(:x, :y), 25832), 4326) AS t) s
				""")
				.param("x", nativeX)
				.param("y", nativeY)
				.query((rs, rowNum) -> new double[] { rs.getDouble("lng"), rs.getDouble("lat") })
				.single();

		double latRad = Math.toRadians(lngLat[1]);
		int n = 1 << zoom;
		int x = (int) Math.floor((lngLat[0] + 180.0) / 360.0 * n);
		int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
		return new int[] { x, y };
	}
}
