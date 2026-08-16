package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.ProjectionDomain;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The other half of the review's request (CONTRACT.md tile size finding): {@link
 * MvtService#SPATIAL_SCATTER} exists to stop {@code candidates}' {@code ORDER BY}
 * falling back to insertion order when {@code ST_Area + ST_Length} ties -- but a real
 * size difference must still decide first, exactly as before. SQL's multi-column
 * {@code ORDER BY} is lexicographic by construction (the second column is only ever
 * consulted once the first compares equal), so this holds for any two differently sized
 * features regardless of magnitude -- this test pins it down concretely rather than
 * leaving it to that guarantee alone.
 *
 * <p>100 squares, side lengths 1..100 m, inserted in a shuffled order decoupled from
 * size: fid and size correlate with nothing, unlike {@code MvtServiceSpatialClumpingTest}
 * on purpose, so a truncation limited to 10 has no fid-order shortcut to accidentally
 * "get right" -- keeping the ten largest is only possible if size genuinely still wins.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MvtServiceSizeTieBreakTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectionDomain projectionDomain;

	@Test
	@DisplayName("a genuine size difference still decides truncation, unaffected by the scatter tie-break")
	void largestFeaturesSurviveTruncationRegardlessOfInsertionOrder() {
		String tableName = SqlIdentifier.tableName(UUID.randomUUID());
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX ON %s USING GIST (geom)".formatted(table)).update();

		// Sizes 1..100, shuffled before insertion: fid 1 is not necessarily side 1, so
		// nothing about insertion order predicts which fids end up largest.
		List<Integer> sides = new ArrayList<>(IntStream.rangeClosed(1, 100).boxed().toList());
		Collections.shuffle(sides, new Random(42));
		for (int side : sides) {
			jdbc.sql("""
					INSERT INTO %s (geom)
					VALUES (ST_Multi(ST_MakeEnvelope(500000, 6000000, 500000 + :side, 6000000 + :side, 25832)))
					""".formatted(table))
					.param("side", side)
					.update();
		}
		jdbc.sql("ANALYZE " + table).update();

		int[] tile = tileFor(500050, 6000050, 10);

		MvtService limited = new MvtService(jdbc, projectionDomain, 10);
		MvtService.RenderedTile rendered =
				limited.renderTile(tableName, 25832, List.of(), List.of(), tile[0], tile[1], tile[2]);

		assertThat(rendered.truncated()).isTrue();

		List<Long> keptFids = MvtTileDecoder.decode(rendered.mvt()).get(0).featureIds();
		// fid N was inserted N-th, i.e. carries sides.get(N - 1).
		List<Integer> keptSides = keptFids.stream().map(fid -> sides.get((int) (fid - 1))).sorted().toList();

		assertThat(keptSides)
				.as("die zehn groessten Quadrate (Seiten 91..100) muessen ueberleben, unabhaengig von der fid-Reihenfolge")
				.containsExactlyElementsOf(IntStream.rangeClosed(91, 100).boxed().toList());
	}

	private int[] tileFor(double nativeX, double nativeY, int zoom) {
		double[] lngLat = jdbc.sql("""
				SELECT ST_X(t) AS lng, ST_Y(t) AS lat
				FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(:x, :y), 25832), 4326) AS t) s
				""")
				.param("x", nativeX).param("y", nativeY)
				.query((rs, rowNum) -> new double[] { rs.getDouble("lng"), rs.getDouble("lat") }).single();
		double latRad = Math.toRadians(lngLat[1]);
		int n = 1 << zoom;
		int x = (int) Math.floor((lngLat[0] + 180.0) / 360.0 * n);
		int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
		return new int[] { zoom, x, y };
	}
}
