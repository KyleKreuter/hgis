package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.TableCreator.CreatedLayer;
import de.kreuter.hgis.events.dto.EventDtos;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * "Ein Import ist ein Ereignis, nicht 46233" (plan "Der Live-Kanal meldet auch
 * Datenaenderungen", 1.3): an import that runs across several batches -- each its own
 * transaction, per {@link ImportTransactions}'s own class comment -- still reaches an
 * open live channel exactly once.
 *
 * <p>{@code catalog_version} itself moves on every batch (proven at the database level in
 * {@code CatalogVersionTriggerTest}), but neither {@link ImportTransactions#begin} nor
 * {@link ImportTransactions#writeBatch} ever calls {@code ChangeLogService.record} or
 * {@code CatalogTouch} directly -- only {@link ImportTransactions#complete} does, once,
 * at the end. That absence, not a dedup trick at publish time, is what keeps a large
 * import from announcing itself once per batch.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ImportCatalogEventTest {

	private static final int SRID = 25832;
	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

	@Autowired
	private ImportTransactions transactions;

	@Autowired
	private JobService jobService;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcClient jdbc;

	@Test
	@DisplayName("begin, two batches and complete -- three separate transactions -- reach the channel exactly once")
	void anImportAcrossSeveralBatchesReachesTheChannelOnce() throws Exception {
		Project project = projectRepository.saveAndFlush(
				new Project("Import-Kanal-Testprojekt " + UUID.randomUUID(), null, SRID, "osm"));
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "test.geojson");
		SourceSchema schema = testSchema();

		MvcResult stream = openStream();
		try {
			// begin() is its own transaction and creates the layer row, but logs nothing
			// (ImportTransactions's own comment: a layer that fails before complete() is
			// dropped whole, as if it never existed) -- no event yet.
			CreatedLayer created = transactions.begin(project, job.getId(), schema, "Kanaltest-Import");

			// Two batches, each its own transaction, each bumping data_version through the
			// bulk @Modifying update -- still no event, since writeBatch never calls
			// ChangeLogService.record either.
			long afterFirst = transactions.writeBatch(created, SRID, SRID, batch(2), job.getId(), 0, null);
			transactions.writeBatch(created, SRID, SRID, batch(1), job.getId(), afterFirst, null);

			assertThat(catalogEventsOf(stream)).as("no event before the import actually finished").isEmpty();

			// complete() is the one transaction that logs anything -- layer.create and
			// feature.insert, both in the same transaction -- and CatalogTouch is what
			// turns those two into a single published event.
			transactions.complete(job.getId(), created.layer().getId(), SRID, 3, 0);

			assertThat(catalogEventsOf(stream))
					.as("one import, one event -- not one per batch and not one per logged action")
					.hasSize(1);

			cleanUp(project, created.layer());
		}
		finally {
			stream.getRequest().getAsyncContext().complete();
		}
	}

	/**
	 * The property the plan's acceptance rule actually asks for -- "ein Ereignis je
	 * logischem Schreibvorgang, unabhaengig von der Stapelgroesse" -- is not established by
	 * any single object count: a batch count of two, as {@link
	 * #anImportAcrossSeveralBatchesReachesTheChannelOnce} uses, could just as well be
	 * something that happens to work for two batches and nothing else. 10 objects is one
	 * batch (below {@link FeatureWriter#BATCH_SIZE}); 10 000 is ten. Both must come out to
	 * exactly one event, or the property does not hold independently of size.
	 */
	@ParameterizedTest(name = "{0} objects reach the channel exactly once, regardless of how many batches that takes")
	@ValueSource(ints = { 10, 10_000 })
	void anImportOfAnySizeReachesTheChannelExactlyOnce(int totalFeatures) throws Exception {
		Project project = projectRepository.saveAndFlush(
				new Project("Import-Kanal-Groessentest " + UUID.randomUUID(), null, SRID, "osm"));
		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "test.geojson");
		SourceSchema schema = testSchema();

		MvcResult stream = openStream();
		try {
			CreatedLayer created = transactions.begin(project, job.getId(), schema, "Groessentest-Import");

			long processed = 0;
			int remaining = totalFeatures;
			while (remaining > 0) {
				int thisBatch = Math.min(FeatureWriter.BATCH_SIZE, remaining);
				processed = transactions.writeBatch(created, SRID, SRID, batch(thisBatch), job.getId(), processed, null);
				remaining -= thisBatch;
			}
			transactions.complete(job.getId(), created.layer().getId(), SRID, totalFeatures, 0);

			assertThat(catalogEventsOf(stream)).hasSize(1);

			cleanUp(project, created.layer());
		}
		finally {
			stream.getRequest().getAsyncContext().complete();
		}
	}

	private static SourceSchema testSchema() {
		return new SourceSchema(GeometryType.MULTIPOINT, SRID,
				List.of(new SourceField("name", String.class)), "UTF-8",
				SourceSchema.CrsConfidence.DECLARED, 3L);
	}

	private static List<SourceFeature> batch(int count) {
		List<SourceFeature> features = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			features.add(new SourceFeature(
					GEOMETRY_FACTORY.createPoint(new Coordinate(9.98 + i * 0.001, 53.55)),
					Map.of("name", "Objekt " + i)));
		}
		return features;
	}

	private void cleanUp(Project project, Layer layer) {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(layer.getTableName())).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		jdbc.sql("DELETE FROM gis_meta.job WHERE project_id = :id").param("id", project.getId()).update();
		projectRepository.deleteById(project.getId());
	}

	private MvcResult openStream() throws Exception {
		return mockMvc.perform(get("/api/events"))
				.andExpect(request().asyncStarted())
				.andReturn();
	}

	private static String textOf(MvcResult stream) throws UnsupportedEncodingException {
		return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private List<JsonNode> catalogEventsOf(MvcResult stream) throws Exception {
		List<JsonNode> events = new ArrayList<>();
		for (String block : textOf(stream).split("\n\n")) {
			boolean isCatalogEvent = false;
			String data = null;
			for (String line : block.split("\n")) {
				if (line.startsWith("event:")) {
					isCatalogEvent = line.substring("event:".length()).trim()
							.equals(EventDtos.EventNames.PROJECT_CATALOG);
				}
				else if (line.startsWith("data:")) {
					data = line.substring("data:".length());
				}
			}
			if (isCatalogEvent && data != null) {
				events.add(objectMapper.readTree(data));
			}
		}
		return events;
	}
}
