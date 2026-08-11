package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.FeatureQueryService;
import de.kreuter.hgis.ingest.reader.SourceReaderFactory;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Nails down the axis order (plan section A.4).
 *
 * EPSG:4326 is officially latitude/longitude; practically every piece of software expects
 * longitude/latitude. {@code HgisBackendApplication} forces longitude-first in a static
 * initialiser, and this test is what keeps that from being quietly removed.
 *
 * A round trip alone would not catch it: swapping the axes on the way in and again on the
 * way out cancels out and looks perfectly correct. So the stored UTM coordinate is checked
 * too -- that is the value that would land in the wrong hemisphere, and nothing about it
 * would ever raise an error.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AxisOrderTest {

	/** St. Michaelis in Hamburg, in EPSG:4326 as GeoJSON writes it: longitude first. */
	private static final double LONGITUDE = 9.9787;
	private static final double LATITUDE = 53.5482;

	/** The same point in EPSG:25832, to a tolerance far tighter than a swap could survive. */
	private static final double EXPECTED_EASTING = 565_000;
	private static final double EXPECTED_NORTHING = 5_934_000;
	private static final double TOLERANCE_METRES = 2_000;

	@Autowired
	private ImportService importService;

	@Autowired
	private FeatureQueryService queryService;

	@Autowired
	private JobService jobService;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Test
	@DisplayName("a point given as lon/lat stays where it was put, through import and back")
	void keepsLongitudeFirstThroughTheWholeChain(@TempDir Path dir) throws IOException {
		Project project = projectRepository.saveAndFlush(
				new Project("Achsen " + UUID.randomUUID(), null, 25832, "osm"));

		Path file = dir.resolve("michel.geojson");
		Files.writeString(file, """
				{"type":"FeatureCollection","features":[
				  {"type":"Feature","geometry":{"type":"Point","coordinates":[%s,%s]},
				   "properties":{"name":"St. Michaelis"}}
				]}""".formatted(LONGITUDE, LATITUDE), StandardCharsets.UTF_8);

		Job job = jobService.create(project.getId(), Job.Type.IMPORT, "michel.geojson");
		try (SourceReader reader = SourceReaderFactory.open(file, null, null)) {
			importService.runImport(job.getId(), project.getId(), reader, "Achsentest", null);
		}

		var result = jobService.get(job.getId());
		UUID layerId = result.outputLayerId();
		assertThat(layerId)
				.as("the import has to succeed for this test to say anything -- job said: %s / %s",
						result.status(), result.message())
				.isNotNull();
		Layer layer = layerRepository.findById(layerId).orElseThrow();

		try {
			// The decisive check. Swapped axes would read 9.9787 as a latitude and 53.5482
			// as a longitude -- a point in the Indian Ocean, stored without a word of
			// complaint, and only noticed once somebody looks at a map.
			// ST_GeometryN because the column is MULTIPOINT: the import promotes every
			// geometry with ST_Multi, so even a lone point is stored as a one-part multi.
			Map<String, Object> stored = jdbc.sql("""
					SELECT ST_X(ST_GeometryN(geom, 1)) AS x, ST_Y(ST_GeometryN(geom, 1)) AS y
					FROM %s
					""".formatted(SqlIdentifier.quoteLayerTable(layer.getTableName())))
					.query()
					.singleRow();

			assertThat((Double) stored.get("x"))
					.as("easting in EPSG:25832 for a point just east of the zone meridian")
					.isCloseTo(EXPECTED_EASTING, org.assertj.core.data.Offset.offset(TOLERANCE_METRES));
			assertThat((Double) stored.get("y"))
					.as("northing in EPSG:25832 for 53.5 degrees north")
					.isCloseTo(EXPECTED_NORTHING, org.assertj.core.data.Offset.offset(TOLERANCE_METRES));

			// And back out again: the feature API returns GeoJSON in 4326, which has to be
			// the coordinate that went in.
			String geoJson = queryService
					.list(layerId, new FeatureQueryService.Query(null, false, null, null, null, true, null, 1))
					.features()
					.get(0)
					.geometry();

			assertThat(geoJson).contains("Point");
			double[] returned = parseFirstCoordinate(geoJson);
			assertThat(returned[0])
					.as("longitude first, as it went in")
					.isCloseTo(LONGITUDE, org.assertj.core.data.Offset.offset(0.0001));
			assertThat(returned[1])
					.as("latitude second")
					.isCloseTo(LATITUDE, org.assertj.core.data.Offset.offset(0.0001));
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(layer.getTableName()))
					.update();
			layerRepository.deleteById(layerId);
			projectRepository.deleteById(project.getId());
		}
	}

	/**
	 * First [lon, lat] pair of a GeoJSON geometry, however deeply it is nested -- a
	 * MultiPoint wraps its coordinates one level further than a Point.
	 */
	private static double[] parseFirstCoordinate(String geoJson) {
		JsonNode node = new ObjectMapper().readTree(geoJson).get("coordinates");
		while (node.get(0).isArray()) {
			node = node.get(0);
		}
		return new double[] { node.get(0).doubleValue(), node.get(1).doubleValue() };
	}
}
