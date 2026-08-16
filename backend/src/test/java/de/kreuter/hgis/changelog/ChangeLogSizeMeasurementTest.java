package de.kreuter.hgis.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.EditService;
import de.kreuter.hgis.features.dto.EditDtos;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Measures, rather than estimates, what {@code change_log.deleted_rows} costs for a
 * realistic mass deletion -- CONTRACT.md "Schreibstufe" 1.2 explicitly asks for this
 * number to be measured, not guessed.
 *
 * <p>100 000 features, a small polygon each (five vertices, promoted to MultiPolygon like
 * every stored geometry) and five attribute columns of modest content -- a typical
 * imported layer's shape, not a worst case and not a toy. Deleted in
 * {@code EditService.MAX_BATCH}-sized batches of 5000, the same ceiling a real client is
 * held to, so this measures exactly what production traffic would produce, not a single
 * oversized call nothing else in the system would ever allow.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChangeLogSizeMeasurementTest {

	private static final Logger log = LoggerFactory.getLogger(ChangeLogSizeMeasurementTest.class);

	private static final int FEATURE_COUNT = 100_000;
	private static final int BATCH_SIZE = 5000; // EditService.MAX_BATCH

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository fieldRepository;

	@Autowired
	private EditService editService;

	@Autowired
	private ChangeLogRepository changeLogRepository;

	@Autowired
	private JdbcClient jdbc;

	private Project project;
	private Layer layer;
	private String tableName;

	@BeforeAll
	void createLayerWithAHundredThousandFeatures() {
		project = projectRepository.saveAndFlush(
				new Project("Protokollgroesse " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom     geometry(MultiPolygon, 25832) NOT NULL,
				    name     text,
				    category text,
				    note     text,
				    height   double precision,
				    count    integer
				)
				""".formatted(table)).update();

		layer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Massentest", tableName, "MULTIPOLYGON", 25832));
		fieldRepository.saveAndFlush(new LayerField(layer, "Name", "name", "text", 0));
		fieldRepository.saveAndFlush(new LayerField(layer, "Kategorie", "category", "text", 1));
		fieldRepository.saveAndFlush(new LayerField(layer, "Notiz", "note", "text", 2));
		fieldRepository.saveAndFlush(new LayerField(layer, "Hoehe", "height", "double precision", 3));
		fieldRepository.saveAndFlush(new LayerField(layer, "Anzahl", "count", "integer", 4));

		// Set-based insert, not one row at a time through the API: this is only fixture
		// setup, and the point of this test is the delete-time capture, not the insert.
		// A five-vertex square, offset by the row number so PostGIS cannot collapse them
		// into one shared buffer -- a modest, realistic building-footprint-sized polygon.
		jdbc.sql("""
				INSERT INTO %s (geom, name, category, note, height, count)
				SELECT
				    ST_Multi(ST_MakeEnvelope(g, g, g + 10, g + 10, 25832)),
				    'Objekt Nr. ' || g,
				    (ARRAY['Wohngebaeude', 'Gewerbe', 'Garage', 'Nebengebaeude'])[1 + (g %% 4)],
				    'Erfasst im Rahmen der Ersterfassung, Bemerkung laufende Nummer ' || g,
				    3.0 + (g %% 20),
				    g %% 100
				FROM generate_series(1, %d) AS g
				""".formatted(table, FEATURE_COUNT)).update();

		long inserted = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
		assertThat(inserted).isEqualTo(FEATURE_COUNT);
	}

	@AfterAll
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	@Test
	@DisplayName("measures the change_log footprint of deleting 100000 features, in EditService.MAX_BATCH-sized batches")
	void measuresChangeLogSizeForAHundredThousandDeletedFeatures() {
		long firstFid = 1;
		long lastFid = FEATURE_COUNT;

		int batches = 0;
		for (long start = firstFid; start <= lastFid; start += BATCH_SIZE) {
			long end = Math.min(start + BATCH_SIZE - 1, lastFid);
			List<Long> fids = LongStream.rangeClosed(start, end).boxed().toList();

			EditDtos.Response response = editService.apply(layer.getId(),
					new EditDtos.Request(null, null, fids, false), "measurement");
			assertThat(response.deleted()).isEqualTo(fids.size());
			batches++;
		}
		assertThat(batches).isEqualTo(FEATURE_COUNT / BATCH_SIZE);

		Long totalBytes = jdbc.sql("""
						SELECT SUM(pg_column_size(deleted_rows))
						FROM gis_meta.change_log
						WHERE layer_id = :layerId AND action = 'feature.delete'
						""")
				.param("layerId", layer.getId())
				.query(Long.class)
				.single();

		Long entryCount = jdbc.sql("""
						SELECT COUNT(*)
						FROM gis_meta.change_log
						WHERE layer_id = :layerId AND action = 'feature.delete'
						""")
				.param("layerId", layer.getId())
				.query(Long.class)
				.single();

		assertThat(entryCount).isEqualTo((long) batches);
		assertThat(totalBytes).isNotNull();

		double megabytes = totalBytes / (1024.0 * 1024.0);
		double bytesPerFeature = (double) totalBytes / FEATURE_COUNT;

		// This is the number CONTRACT.md "Schreibstufe" 1.2 asks the report to carry --
		// measured here, not estimated. Printed as well as logged so it survives even a
		// quiet Maven run (-q suppresses INFO, not System.out).
		String measurement = "change_log size for %d deleted features (%d batches of %d, "
				.formatted(FEATURE_COUNT, batches, BATCH_SIZE)
				+ "5 attribute columns, small polygon each): %d bytes total = %.2f MB, %.1f bytes/feature"
						.formatted(totalBytes, megabytes, bytesPerFeature);
		log.info(measurement);
		System.out.println("MEASUREMENT: " + measurement);
	}
}
