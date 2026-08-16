package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.ProjectionDomain;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A second, independent reproduction of the same finding {@link MvtServiceSpatialClumpingTest}
 * covers, from a separate review pass: two equal-sized, spatially disjoint groups of
 * points, one inserted before the other. Deliberately built differently from that class
 * rather than merged into it -- a {@code z=0} tile (the whole world, one tile, no grid
 * line anywhere to land on the wrong side of) sidesteps that class's own history with a
 * mismeasured tile boundary, and the group is carried as an actual tile attribute
 * ({@code grp}) rather than inferred from a fid range, closer to how a real client would
 * read it back.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SpatialClusteringProbeTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectionDomain projectionDomain;

	@Test
	@DisplayName("truncation splits across two equal-sized, spatially disjoint point groups instead of keeping only the first inserted")
	void pointLayerTruncationKeepsBothGroupsRepresented() {
		String tableName = SqlIdentifier.tableName(UUID.randomUUID());
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    grp  text,
				    geom geometry(MultiPoint, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX ON %s USING GIST (geom)".formatted(table)).update();

		int perGroup = 5_000;

		// Group A first (lower fid), tightly packed near Hamburg-Wandsbek.
		jdbc.sql("""
				INSERT INTO %s (geom, grp)
				SELECT ST_Multi(ST_SetSRID(ST_MakePoint(570000 + random() * 1000, 5935000 + random() * 1000), 25832)), 'A'
				FROM generate_series(1, :count)
				""".formatted(table))
				.param("count", perGroup)
				.update();

		// Group B second (higher fid), tightly packed near Hamburg-Altona -- spatially
		// disjoint from group A, both comfortably inside one z=0 tile.
		jdbc.sql("""
				INSERT INTO %s (geom, grp)
				SELECT ST_Multi(ST_SetSRID(ST_MakePoint(550000 + random() * 1000, 5935000 + random() * 1000), 25832)), 'B'
				FROM generate_series(1, :count)
				""".formatted(table))
				.param("count", perGroup)
				.update();
		jdbc.sql("ANALYZE " + table).update();

		int limit = 5_000;
		MvtService limited = new MvtService(jdbc, projectionDomain, limit);
		MvtService.RenderedTile rendered =
				limited.renderTile(tableName, 25832, List.of("grp"), List.of(), 0, 0, 0);

		assertThat(rendered.truncated()).isTrue();

		Map<String, Long> byGroup = MvtTileDecoder.decode(rendered.mvt()).get(0).features().stream()
				.collect(Collectors.groupingBy(f -> String.valueOf(f.properties().get("grp")),
						Collectors.counting()));

		assertThat(byGroup.values().stream().mapToLong(Long::longValue).sum())
				.as("die Kachel muss genau das Limit an Objekten liefern")
				.isEqualTo(limit);
		// Expected under an unbiased scatter: 2.500 each, standard deviation around 25
		// (hypergeometric, n=5.000 drawn from 10.000 split 5.000/5.000). The bug this
		// guards against kept only group A (the first-inserted, lower-fid group) whole
		// and dropped group B to zero -- a band this wide still fails outright on that.
		assertThat(byGroup.getOrDefault("A", 0L))
				.as("Gruppe A (niedrige fid) darf nicht die ganze Kachel fuer sich haben")
				.isBetween(2000L, 3000L);
		assertThat(byGroup.getOrDefault("B", 0L))
				.as("Gruppe B (hohe fid) darf nicht vollstaendig herausfallen")
				.isBetween(2000L, 3000L);
	}
}
