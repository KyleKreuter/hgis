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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * <p>100 000 features per scenario, deleted in {@code EditService.MAX_BATCH}-sized
 * batches of 5000, the same ceiling a real client is held to -- so this measures exactly
 * what production traffic would produce, not a single oversized call nothing else in the
 * system would ever allow.
 *
 * <p>Two numbers are reported for each scenario, and a first pass at this measurement
 * only reported one of them under a name that did not say which: {@code pg_column_size}
 * measures the row's on-disk footprint <em>after</em> PostgreSQL's TOAST compression, not
 * what a reader gets. {@code octet_length(deleted_rows::text)} is the uncompressed JSON
 * text -- what actually crosses the wire on {@code GET .../changes?includeDeletedRows=true},
 * and for text-heavy JSON like this, compression buys roughly a factor of two to three,
 * so a disk-footprint number alone understates the size of a row a client would receive.
 *
 * <p>Both bytes/feature numbers are specific to each scenario's own vertex count, not a
 * ceiling for "realistic" data in general: a review of this measurement traced the ~578
 * byte gap between the two scenarios' uncompressed numbers to geometry size alone (182
 * bytes of GeoJSON for the 5-vertex box, 516 bytes for the 16-vertex building, matching
 * the measured gap to the decimal), which comes out to roughly 30 bytes per vertex. A
 * real, non-convex building outline with wings and notches routinely has far more than 16
 * vertices -- 32 vertices already adds roughly 780 bytes over the 5-vertex box, 64
 * vertices roughly 1670 -- so a capacity plan built on this test's numbers should scale
 * them by the vertex count of the data being planned for, not read {@link
 * #measuresSizeForARealisticBuildingOutlineWithVariedText}'s number as an upper bound.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
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
	private JdbcClient jdbc;

	@Test
	@DisplayName("measures the change_log footprint for 100000 deleted features: a small five-vertex "
			+ "polygon with fairly repetitive text, compressed and uncompressed")
	void measuresSizeForASmallPolygonWithRepetitiveText() {
		measure("small polygon (5 vertices), fairly repetitive text",
				"ST_Multi(ST_MakeEnvelope(g, g, g + 10, g + 10, 25832))",
				"'Erfasst im Rahmen der Ersterfassung, Bemerkung laufende Nummer ' || g");
	}

	@Test
	@DisplayName("measures the change_log footprint for 100000 deleted features: a realistic 16-vertex "
			+ "building outline with shorter, more varied text")
	void measuresSizeForARealisticBuildingOutlineWithVariedText() {
		// quad_segs=4 on a buffered point is a precise way to get a fixed, realistic
		// vertex count per feature (4 segments per quarter circle, 16 total) rather than
		// the artificially small 5-vertex box the other scenario uses -- CONTRACT.md's
		// own example of what the first pass at this measurement had not tried.
		// A single '%', not '%%': this string is substituted into the outer text block's
		// own .formatted() call as a %s argument, which inserts it verbatim -- unlike the
		// text block's own literal modulo operators below, an argument is never itself
		// re-scanned for % escapes.
		measure("realistic building outline (16 vertices), shorter varied text",
				"ST_Multi(ST_Buffer(ST_MakePoint(g, g), 5, 'quad_segs=4'))",
				"'Baujahr ' || (1900 + (g % 125)) || ', Bauteil ' || (g % 12)");
	}

	private void measure(String description, String geometryExpression, String noteExpression) {
		Project project = projectRepository.saveAndFlush(
				new Project("Protokollgroesse " + UUID.randomUUID(), null, 25832, "osm"));
		UUID layerId = UUID.randomUUID();
		String tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);

		try {
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

			Layer layer = layerRepository.saveAndFlush(
					new Layer(layerId, project, "Massentest", tableName, "MULTIPOLYGON", 25832));
			fieldRepository.saveAndFlush(new LayerField(layer, "Name", "name", "text", 0));
			fieldRepository.saveAndFlush(new LayerField(layer, "Kategorie", "category", "text", 1));
			fieldRepository.saveAndFlush(new LayerField(layer, "Notiz", "note", "text", 2));
			fieldRepository.saveAndFlush(new LayerField(layer, "Hoehe", "height", "double precision", 3));
			fieldRepository.saveAndFlush(new LayerField(layer, "Anzahl", "count", "integer", 4));

			// Set-based insert, not one row at a time through the API: this is only fixture
			// setup, and the point of this test is the delete-time capture, not the insert.
			jdbc.sql("""
					INSERT INTO %s (geom, name, category, note, height, count)
					SELECT
					    %s,
					    'Objekt Nr. ' || g,
					    (ARRAY['Wohngebaeude', 'Gewerbe', 'Garage', 'Nebengebaeude'])[1 + (g %% 4)],
					    %s,
					    3.0 + (g %% 20),
					    g %% 100
					FROM generate_series(1, %d) AS g
					""".formatted(table, geometryExpression, noteExpression, FEATURE_COUNT)).update();

			long inserted = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
			assertThat(inserted).isEqualTo(FEATURE_COUNT);

			int batches = 0;
			for (long start = 1; start <= FEATURE_COUNT; start += BATCH_SIZE) {
				long end = Math.min(start + BATCH_SIZE - 1, FEATURE_COUNT);
				List<Long> fids = LongStream.rangeClosed(start, end).boxed().toList();

				EditDtos.Response response = editService.apply(layer.getId(),
						new EditDtos.Request(null, null, fids, false), "measurement");
				assertThat(response.deleted()).isEqualTo(fids.size());
				batches++;
			}
			assertThat(batches).isEqualTo(FEATURE_COUNT / BATCH_SIZE);

			Long compressedBytes = jdbc.sql("""
							SELECT SUM(pg_column_size(deleted_rows))
							FROM gis_meta.change_log
							WHERE layer_id = :layerId AND action = 'feature.delete'
							""")
					.param("layerId", layer.getId())
					.query(Long.class)
					.single();
			Long uncompressedBytes = jdbc.sql("""
							SELECT SUM(octet_length(deleted_rows::text))
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
			assertThat(compressedBytes).isNotNull();
			assertThat(uncompressedBytes).isNotNull();

			report(description, compressedBytes, uncompressedBytes);
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + table).update();
			layerRepository.findById(layerId).ifPresent(layerRepository::delete);
			projectRepository.deleteById(project.getId());
		}
	}

	private void report(String description, long compressedBytes, long uncompressedBytes) {
		double compressedMb = compressedBytes / (1024.0 * 1024.0);
		double uncompressedMb = uncompressedBytes / (1024.0 * 1024.0);
		double compressedPerFeature = (double) compressedBytes / FEATURE_COUNT;
		double uncompressedPerFeature = (double) uncompressedBytes / FEATURE_COUNT;

		// This is the number CONTRACT.md "Schreibstufe" 1.2 asks the report to carry --
		// measured here, not estimated, and now labelled with which of the two questions
		// each figure actually answers. Printed as well as logged so it survives even a
		// quiet Maven run (-q suppresses INFO, not System.out).
		String measurement = ("change_log size for %d deleted features (%s): "
				+ "on disk (TOAST-compressed, pg_column_size) %d bytes = %.2f MB, %.1f bytes/feature; "
				+ "on the wire (uncompressed JSON text, octet_length) %d bytes = %.2f MB, %.1f bytes/feature")
				.formatted(FEATURE_COUNT, description,
						compressedBytes, compressedMb, compressedPerFeature,
						uncompressedBytes, uncompressedMb, uncompressedPerFeature);
		log.info(measurement);
		System.out.println("MEASUREMENT: " + measurement);
	}
}
