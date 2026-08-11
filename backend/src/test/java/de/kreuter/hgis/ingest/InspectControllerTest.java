package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.ingest.reader.support.TestShapefiles;
import de.kreuter.hgis.ingest.reader.support.TestZips;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The import preview over HTTP.
 *
 * The encoding cases are the reason this endpoint exists: a charset name means nothing to
 * the person in front of the dialog, while {@code Müllerstraße} against
 * {@code MÃ¼llerstraÃŸe} against {@code M?llerstra?e} is a difference anybody can decide.
 * So both directions of getting it wrong are exercised here with real bytes -- Windows-1252
 * read as UTF-8, and UTF-8 read as Windows-1252 -- and each time through the second
 * inspection of an upload that was transferred exactly once.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InspectControllerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

	/** Hamburg in EPSG:25832 -- far enough outside the degree ranges to be guessed as UTM. */
	private static final String UTM_CSV = """
			rechtswert;hochwert;strasse;hausnr
			566000,00;5934000,00;Müllerstraße;12
			566100,00;5934100,00;Bäckerweg;
			566200,00;5934200,00;Großer Burstah;7
			""";

	/** Long enough for an async import of three features on a busy CI machine. */
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
	private String importedTableName;

	@BeforeEach
	void setUp() {
		projectId = projectRepository.saveAndFlush(
				new Project("Vorschau-Testprojekt " + UUID.randomUUID(), null, 25832, "osm")).getId();
	}

	@AfterEach
	void tearDown() {
		if (importedTableName != null) {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(importedTableName)).update();
		}
		jdbc.sql("DELETE FROM gis_meta.project WHERE id = :id").param("id", projectId).update();
	}

	@Test
	@DisplayName("meldet Schema, Wertevorschau und Verortung einer CSV")
	void reportsSchemaSampleValuesAndLocation(@TempDir Path dir) throws Exception {
		JsonNode inspection = inspect(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252));

		assertThat(inspection.get("filename").asString()).isEqualTo("adressen.csv");
		assertThat(inspection.get("geometryType").asString()).isEqualTo("MULTIPOINT");
		assertThat(inspection.get("srid").asInt()).isEqualTo(25832);
		assertThat(inspection.get("crsConfidence").asString()).isEqualTo("GUESSED");
		assertThat(inspection.get("charset").asString()).isEqualTo("windows-1252");
		assertThat(inspection.get("uploadId").asString()).isNotBlank();

		// The extent has to arrive in WGS 84 whatever the source CRS was, or the frontend
		// would place UTM eastings somewhere off the coast of Africa.
		JsonNode extent = inspection.get("extentWgs84");
		assertThat(extent.get(0).asDouble()).isBetween(9.0, 11.0);
		assertThat(extent.get(1).asDouble()).isBetween(53.0, 54.0);
		assertThat(extent.get(2).asDouble()).isBetween(9.0, 11.0);
		assertThat(extent.get(3).asDouble()).isBetween(53.0, 54.0);

		JsonNode strasse = fieldNamed(inspection, "strasse");
		assertThat(strasse.get("dataType").asString()).isEqualTo("text");
		assertThat(sampleValues(strasse)).containsExactly("Müllerstraße", "Bäckerweg", "Großer Burstah");

		// The geometry columns are consumed by the reader and must not show up as attributes.
		assertThat(inspection.get("fields")).hasSize(2);
	}

	@Test
	@DisplayName("liefert null als null, nicht als leere Zeichenkette")
	void reportsAMissingValueAsNull(@TempDir Path dir) throws Exception {
		JsonNode inspection = inspect(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252));

		JsonNode hausnummern = fieldNamed(inspection, "hausnr").get("sampleValues");
		assertThat(hausnummern.get(1).isNull())
				.as("an empty cell is a missing value, and the dialog has to be able to show that")
				.isTrue();
	}

	@Test
	@DisplayName("zeigt Umlaute bei falsch geratener Kodierung sichtbar kaputt")
	void showsUmlautsBrokenWhenTheEncodingIsGuessedWrong(@TempDir Path dir) throws Exception {
		JsonNode detected = inspect(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252));
		assertThat(sampleValues(fieldNamed(detected, "strasse"))).contains("Müllerstraße");

		String uploadId = detected.get("uploadId").asString();
		JsonNode misread = inspect(request -> request.param("uploadId", uploadId).param("charset", "UTF-8"));

		assertThat(misread.get("uploadId").asString())
				.as("the same file, not a second upload")
				.isEqualTo(uploadId);
		assertThat(misread.get("charset").asString()).isEqualTo("UTF-8");
		assertThat(sampleValues(fieldNamed(misread, "strasse")).get(0))
				.doesNotContain("Müllerstraße")
				.contains("�");
	}

	@Test
	@DisplayName("zeigt UTF-8 als Windows-1252 gelesen als Buchstabensalat")
	void showsMojibakeWhenUtf8IsReadAsWindows1252(@TempDir Path dir) throws Exception {
		JsonNode detected = inspect(upload(dir, "adressen.csv", UTM_CSV, StandardCharsets.UTF_8));
		assertThat(sampleValues(fieldNamed(detected, "strasse"))).contains("Müllerstraße");

		String uploadId = detected.get("uploadId").asString();
		JsonNode misread = inspect(request -> request.param("uploadId", uploadId).param("charset", "windows-1252"));

		assertThat(sampleValues(fieldNamed(misread, "strasse")).get(0)).isEqualTo("MÃ¼llerstraÃŸe");
	}

	@Test
	@DisplayName("übernimmt eine vom Nutzer gesetzte SRID für die Verortung")
	void honoursAUserSuppliedSrid(@TempDir Path dir) throws Exception {
		JsonNode detected = inspect(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252));
		String uploadId = detected.get("uploadId").asString();

		JsonNode corrected = inspect(request -> request.param("uploadId", uploadId).param("srid", "25833"));

		assertThat(corrected.get("srid").asInt()).isEqualTo(25833);
		assertThat(corrected.get("crsConfidence").asString()).isEqualTo("DECLARED");
		assertThat(corrected.get("extentWgs84").get(0).asDouble())
				.as("zone 33 puts the same eastings a good deal further east")
				.isGreaterThan(detected.get("extentWgs84").get(0).asDouble());
	}

	@Test
	@DisplayName("meldet Kodierung eines Shapefiles und dessen Objektzahl")
	void reportsCharsetAndFeatureCountOfAShapefile(@TempDir Path dir) throws Exception {
		Path shapefile = TestShapefiles.writeWithAttributeValue(
				dir, "gebaeude", "Müllerstraße", TestShapefiles.CharsetHint.CPG_FILE);
		Path zip = TestZips.zipShapefileSet(shapefile, dir.resolve("gebaeude.zip"));

		JsonNode inspection = inspect(new MockMultipartFile(
				"file", "gebaeude.zip", "application/zip", Files.readAllBytes(zip)));

		assertThat(inspection.get("charset").asString()).isEqualTo("windows-1252");
		assertThat(inspection.get("featureCount").asLong())
				.as("a shapefile knows its total up front")
				.isEqualTo(1);
		assertThat(inspection.get("geometryType").asString()).isEqualTo("MULTIPOINT");
		assertThat(sampleValues(fieldNamed(inspection, "name"))).containsExactly("Müllerstraße");
	}

	@Test
	@DisplayName("meldet keine Kodierung für ein Format, das keine Wahl lässt")
	void reportsNoCharsetForAFormatThatLeavesNoChoice(@TempDir Path dir) throws Exception {
		MockMultipartFile geojson = upload(dir, "orte.geojson", """
				{"type":"FeatureCollection","features":[
				  {"type":"Feature","geometry":{"type":"Point","coordinates":[9.99,53.55]},
				   "properties":{"strasse":"Müllerstraße"}}
				]}
				""", StandardCharsets.UTF_8);

		JsonNode inspection = inspect(geojson);

		assertThat(inspection.get("charset").isNull())
				.as("GeoJSON is UTF-8 by specification -- offering a choice would be a lie")
				.isTrue();
		assertThat(inspection.get("srid").asInt()).isEqualTo(4326);
		assertThat(inspection.get("crsConfidence").asString()).isEqualTo("ASSUMED");
		assertThat(inspection.get("featureCount").isNull())
				.as("GeoJSON does not know its total before it is read")
				.isTrue();
		assertThat(sampleValues(fieldNamed(inspection, "strasse"))).containsExactly("Müllerstraße");
	}

	@Test
	@DisplayName("legt beim Inspizieren keinen Job an")
	void createsNoJobWhileInspecting(@TempDir Path dir) throws Exception {
		long before = countJobs();

		inspect(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252));

		assertThat(countJobs()).isEqualTo(before);
	}

	@Test
	@DisplayName("importiert eine bereits übertragene Datei über ihre uploadId")
	void importsAPreviouslyUploadedFileByItsUploadId(@TempDir Path dir) throws Exception {
		JsonNode inspection = inspect(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252));
		String uploadId = inspection.get("uploadId").asString();

		String body = mockMvc.perform(multipart("/api/projects/{id}/imports", projectId)
						.param("uploadId", uploadId)
						.param("name", "Adressen"))
				.andExpect(status().isAccepted())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		JsonNode job = MAPPER.readTree(body);
		assertThat(job.get("filename").asString())
				.as("the job names the file the user uploaded, not the one on disk")
				.isEqualTo("adressen.csv");

		JsonNode finished = awaitJob(job.get("id").asString());
		assertThat(finished.get("status").asString())
				.as("import failed: %s", finished.get("message"))
				.isEqualTo("SUCCEEDED");

		Layer layer = layerRepository.findById(UUID.fromString(finished.get("outputLayerId").asString()))
				.orElseThrow();
		importedTableName = layer.getTableName();
		assertThat(layer.getFeatureCount()).isEqualTo(3);

		String stored = jdbc.sql("SELECT strasse FROM " + SqlIdentifier.quoteLayerTable(importedTableName)
						+ " ORDER BY fid LIMIT 1")
				.query(String.class)
				.single();
		assertThat(stored)
				.as("the encoding chosen in the preview has to be the one the import uses")
				.isEqualTo("Müllerstraße");

		mockMvc.perform(multipart("/api/projects/{id}/imports/inspect", projectId).param("uploadId", uploadId))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("weist Datei und uploadId gleichzeitig ab")
	void rejectsAFileAndAnUploadIdTogether(@TempDir Path dir) throws Exception {
		JsonNode inspection = inspect(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252));

		mockMvc.perform(multipart("/api/projects/{id}/imports/inspect", projectId)
						.file(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252))
						.param("uploadId", inspection.get("uploadId").asString()))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("weist eine Anfrage ohne Datei und ohne uploadId ab")
	void rejectsARequestWithNeitherFileNorUploadId() throws Exception {
		mockMvc.perform(multipart("/api/projects/{id}/imports/inspect", projectId))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("meldet einen abgelaufenen Upload als nicht mehr vorhanden")
	void reportsAnExpiredUploadAsGone() throws Exception {
		mockMvc.perform(multipart("/api/projects/{id}/imports/inspect", projectId)
						.param("uploadId", UUID.randomUUID().toString()))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("meldet eine unlesbare Datei unter ihrem Originalnamen")
	void namesTheUploadedFileInAnErrorMessage(@TempDir Path dir) throws Exception {
		MockMultipartFile broken = upload(dir, "kaputt.csv", "spalte_ohne_geometrie\nwert\n", StandardCharsets.UTF_8);

		String body = mockMvc.perform(multipart("/api/projects/{id}/imports/inspect", projectId).file(broken))
				.andExpect(status().isBadRequest())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		assertThat(MAPPER.readTree(body).get("detail").asString())
				.as("the user never named a file 'upload.csv'")
				.contains("kaputt.csv")
				.doesNotContain("upload.csv");
	}

	@Test
	@DisplayName("weist eine SRID ab, die die Datenbank nicht kennt")
	void rejectsAnSridTheDatabaseDoesNotKnow(@TempDir Path dir) throws Exception {
		mockMvc.perform(multipart("/api/projects/{id}/imports/inspect", projectId)
						.file(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252))
						.param("srid", "999999"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("meldet ein unbekanntes Projekt als nicht gefunden")
	void reportsAnUnknownProjectAsMissing(@TempDir Path dir) throws Exception {
		mockMvc.perform(multipart("/api/projects/{id}/imports/inspect", UUID.randomUUID())
						.file(upload(dir, "adressen.csv", UTM_CSV, WINDOWS_1252)))
				.andExpect(status().isNotFound());
	}

	// --- steps ---------------------------------------------------------------------------

	private static MockMultipartFile upload(Path dir, String filename, String content, Charset charset)
			throws IOException {
		// Written through a real file so the bytes are exactly what the charset produces,
		// not what a String literal happens to carry.
		Path file = dir.resolve(filename);
		Files.write(file, content.getBytes(charset));
		return new MockMultipartFile("file", filename, "text/csv", Files.readAllBytes(file));
	}

	private JsonNode inspect(MockMultipartFile file) throws Exception {
		return inspect(request -> request.file(file));
	}

	private JsonNode inspect(UnaryOperator<MockMultipartHttpServletRequestBuilder> parameters)
			throws Exception {
		MockMultipartHttpServletRequestBuilder request =
				multipart("/api/projects/{id}/imports/inspect", projectId);

		String body = mockMvc.perform(parameters.apply(request))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		return MAPPER.readTree(body);
	}

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

	private long countJobs() {
		return jdbc.sql("SELECT COUNT(*) FROM gis_meta.job").query(Long.class).single();
	}

	private static JsonNode fieldNamed(JsonNode inspection, String name) {
		for (JsonNode field : inspection.get("fields")) {
			if (field.get("name").asString().equals(name)) {
				return field;
			}
		}
		throw new AssertionError("Feld '" + name + "' fehlt in der Antwort: " + inspection.get("fields"));
	}

	private static List<String> sampleValues(JsonNode field) {
		List<String> values = new ArrayList<>();
		for (JsonNode value : field.get("sampleValues")) {
			values.add(value.isNull() ? null : value.asString());
		}
		return values;
	}
}
