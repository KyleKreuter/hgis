package de.kreuter.hgis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.ingest.reader.support.TestShapefiles;
import de.kreuter.hgis.ingest.reader.support.TestZips;
import de.kreuter.hgis.tiles.MvtTileDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole chain over HTTP: upload a file, wait for the job, read the catalog, fetch a
 * tile and decode it, write an edit batch, and check what moved.
 *
 * Every step has its own focused test elsewhere. This one exists for what those cannot
 * see: that the steps still fit together. The seams between import, tiles and editing are
 * where the version counters, the extent and the feature count are handed on -- and a
 * mistake there breaks nothing locally while quietly making the map wrong.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EndToEndTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Long enough for an async import of a handful of features on a busy CI machine. */
	private static final int MAX_POLLS = 100;
	private static final long POLL_INTERVAL_MS = 100;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private JdbcClient jdbc;

	private UUID projectId;
	private String tableName;

	@AfterEach
	void cleanUp() {
		if (tableName != null) {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		}
		if (projectId != null) {
			jdbc.sql("DELETE FROM gis_meta.project WHERE id = :id").param("id", projectId).update();
		}
	}

	@Test
	@DisplayName("upload, tile, edit -- the whole chain, over HTTP")
	void importsRendersAndEdits(@TempDir Path dir) throws Exception {
		projectId = createProject();

		// --- import -------------------------------------------------------------------
		Path shapefile = TestShapefiles.writeWithAttributeValue(
				dir, "gebaeude", "Müllerstraße", TestShapefiles.CharsetHint.CPG_FILE);
		Path zip = TestZips.zipShapefileSet(shapefile, dir.resolve("gebaeude.zip"));

		JsonNode job = startImport(zip);
		JsonNode finished = awaitJob(job.get("id").asString());

		assertThat(finished.get("status").asString())
				.as("import failed: %s", finished.get("message"))
				.isEqualTo("SUCCEEDED");

		UUID layerId = UUID.fromString(finished.get("outputLayerId").asString());
		Layer layer = layerRepository.findById(layerId).orElseThrow();
		tableName = layer.getTableName();

		assertThat(layer.getFeatureCount()).isPositive();
		assertThat(layer.getExtent()).as("the map needs it to pick an opening view").isNotNull();

		// The umlaut has to survive the whole way: .cpg -> DBF -> GeoTools -> PostgreSQL.
		String stored = jdbc.sql("SELECT name FROM " + SqlIdentifier.quoteLayerTable(tableName)
						+ " LIMIT 1")
				.query(String.class)
				.single();
		assertThat(stored).isEqualTo("Müllerstraße");

		// --- tile ---------------------------------------------------------------------
		long dataVersionAfterImport = layer.getDataVersion();
		List<Long> idsInTile = fetchTileFeatureIds(layerId, layer);
		assertThat(idsInTile)
				.as("the imported features have to show up in a tile")
				.isNotEmpty();

		// --- edit ---------------------------------------------------------------------
		long featureCountBefore = layer.getFeatureCount();
		long victim = idsInTile.get(0);

		String response = mockMvc.perform(post("/api/layers/{id}/edits", layerId)
						.contentType(MediaType.APPLICATION_JSON)
						// A point, because the fixture is a point layer -- sending a polygon
						// here is refused, and rightly so.
						.content("""
								{"creates":[{"clientId":-1,
								  "geometry":{"type":"Point","coordinates":[9.985,53.545]},
								  "properties":{"name":"Von Hand gesetzt"}}],
								 "deletes":[%d]}
								""".formatted(victim)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		JsonNode edit = MAPPER.readTree(response);
		assertThat(edit.get("deleted").asInt()).isEqualTo(1);
		assertThat(edit.get("createdFids").get("-1").asLong()).isPositive();
		assertThat(edit.get("featureCount").asLong())
				.as("one gone, one added")
				.isEqualTo(featureCountBefore);
		assertThat(edit.get("dataVersion").asLong())
				.as("without a bump the map would keep serving the tiles from before the edit")
				.isGreaterThan(dataVersionAfterImport);

		// --- and the tile follows -----------------------------------------------------
		Layer reloaded = layerRepository.findById(layerId).orElseThrow();
		List<Long> idsAfterEdit = fetchTileFeatureIds(layerId, reloaded);

		assertThat(idsAfterEdit)
				.as("the deleted feature must be gone from the rendered tile")
				.doesNotContain(victim);
	}

	// --- steps --------------------------------------------------------------------------

	private UUID createProject() throws Exception {
		String body = mockMvc.perform(post("/api/projects")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Durchstich %s\",\"srid\":25832}"
								.formatted(UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		return UUID.fromString(MAPPER.readTree(body).get("id").asString());
	}

	private JsonNode startImport(Path zip) throws Exception {
		MockMultipartFile upload = new MockMultipartFile(
				"file", "gebaeude.zip", "application/zip", Files.readAllBytes(zip));

		String body = mockMvc.perform(multipart("/api/projects/{id}/imports", projectId).file(upload))
				.andExpect(status().isAccepted())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		return MAPPER.readTree(body);
	}

	/** The import runs asynchronously, so the only way through the HTTP API is to poll. */
	private JsonNode awaitJob(String jobId) throws Exception {
		for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
			String body = mockMvc.perform(get("/api/jobs/{id}", jobId))
					.andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString(StandardCharsets.UTF_8);

			JsonNode job = MAPPER.readTree(body);
			String status = job.get("status").asString();
			if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
				return job;
			}
			Thread.sleep(POLL_INTERVAL_MS);
		}
		throw new AssertionError("Import-Job wurde binnen "
				+ (MAX_POLLS * POLL_INTERVAL_MS) + " ms nicht fertig");
	}

	/**
	 * Fetches the tile covering the layer's extent and decodes it. Uses the layer's own
	 * versions in the URL, exactly as the client builds it.
	 */
	private List<Long> fetchTileFeatureIds(UUID layerId, Layer layer) throws Exception {
		var envelope = layer.getExtent().getEnvelopeInternal();
		int zoom = 14;
		int x = lonToTileX(envelope.getMinX() + envelope.getWidth() / 2, zoom);
		int y = latToTileY(envelope.getMinY() + envelope.getHeight() / 2, zoom);

		byte[] tile = mockMvc.perform(get("/api/layers/{id}/tiles/{z}/{x}/{y}.mvt", layerId, zoom, x, y)
						.param("v", layer.getDataVersion() + "." + layer.getStyleVersion()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		return MvtTileDecoder.decode(tile).stream()
				.flatMap(decoded -> decoded.featureIds().stream())
				.toList();
	}

	private static int lonToTileX(double lon, int zoom) {
		return (int) Math.floor((lon + 180) / 360 * (1 << zoom));
	}

	private static int latToTileY(double lat, int zoom) {
		double radians = Math.toRadians(lat);
		return (int) Math.floor(
				(1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2 * (1 << zoom));
	}
}
