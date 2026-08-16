package de.kreuter.hgis.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.ProjectionDomain;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The finding a code review turned up: on a point layer, {@code ST_Area} and
 * {@code ST_Length} are 0 for every row, so {@code candidates}' ranking used to fall
 * straight through to {@code fid} -- physical insert order. A source sorted by district
 * (an ordinary shape for a government export) keeps that order into the table, so a
 * limited tile did not thin the layer out, it kept one district whole and dropped its
 * neighbour whole. Reproduced here exactly as the review found it: two 20.000-point
 * "Bezirke" on opposite sides of the same tile, a limit that can only hold one district's
 * worth. {@link MvtService#SPATIAL_SCATTER} is what fixes it, by no longer breaking the
 * {@code ST_Area + ST_Length} tie with plain {@code fid}.
 *
 * <p>The review's own zoom, 6, turned out to sit right on a tile boundary between the two
 * districts for these coordinates -- one district's centre projects to tile x=33, the
 * other's to x=34 -- so a {@code WHERE l.geom && b.native} at z=6 only ever saw "Bezirk
 * A" in the first place, no ordering involved. z=3 is the fix: verified below (all four
 * corners of the combined extent, and the true row counts) to place the whole extent in
 * one tile, the same z=3/x=4/y=2 an existing {@code MvtServiceTest} fixture already
 * documents as squarely inside EPSG:25832's projectable domain.
 *
 * <p>A second review pass sharpened the mechanism further: the bug is not "point layers" --
 * it is "{@code ST_Area + ST_Length} ties for many rows", which points guarantee (always
 * 0) but are not the only way to reach. {@code LayerTableFixture} in this very package
 * shows it by accident: every "signal" feature is an identical 10x10 box, so it too would
 * have fallen straight through to {@code fid} before this fix. {@link
 * #uniformPolygonsClumpTheSameWayPointsDo()} proves the fix covers that shape as well,
 * without a point in sight.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MvtServiceSpatialClumpingTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectionDomain projectionDomain;

	@Test
	@DisplayName("a truncated point tile keeps both spatially clumped districts represented, not one whole and one dropped")
	void truncationSplitsAcrossSpatiallyClumpedInsertOrder() {
		String tableName = SqlIdentifier.tableName(UUID.randomUUID());
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPoint, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX ON %s USING GIST (geom)".formatted(table)).update();

		// Two districts, both inside the same tile, on opposite sides of it. "Bezirk A"
		// (low fids, inserted first) sits in the west, "Bezirk B" (high fids, inserted
		// second) sits in the east -- exactly what a shapefile sorted by Bezirk name
		// would produce on INSERT ... SELECT in file order.
		int perDistrict = 20_000;
		jdbc.sql("""
				INSERT INTO %s (geom)
				SELECT ST_Multi(ST_SetSRID(ST_MakePoint(500000 + (random() * 20000), 5900000 + (random() * 400000)), 25832))
				FROM generate_series(1, :count)
				""".formatted(table))
				.param("count", perDistrict)
				.update();
		jdbc.sql("""
				INSERT INTO %s (geom)
				SELECT ST_Multi(ST_SetSRID(ST_MakePoint(700000 + (random() * 20000), 5900000 + (random() * 400000)), 25832))
				FROM generate_series(1, :count)
				""".formatted(table))
				.param("count", perDistrict)
				.update();
		jdbc.sql("ANALYZE " + table).update();

		int[] tile = tileCoveringBoth(jdbc);
		int limit = 5_000;

		// The precondition this whole test depends on: the chosen tile must actually
		// see every one of the 40.000 rows before any limit is applied. Without this,
		// a mismeasured tile (see the class Javadoc) can reproduce the exact same
		// symptom -- one district missing -- for a completely different reason, and
		// silently point the fix at the wrong bug.
		long totalBeforeLimit = jdbc.sql("""
				SELECT count(*) FROM %s l,
				  (SELECT ST_Transform(ST_Segmentize(ST_TileEnvelope(:z, :x, :y), 100000), 25832) AS native) b
				WHERE l.geom && b.native
				""".formatted(table))
				.param("z", tile[0]).param("x", tile[1]).param("y", tile[2])
				.query(Long.class).single();
		assertThat(totalBeforeLimit)
				.as("Testvoraussetzung: die Kachel muss beide Bezirke vollstaendig sehen, vor jeder Kuerzung")
				.isEqualTo(2L * perDistrict);

		MvtService limited = new MvtService(jdbc, projectionDomain, limit);
		MvtService.RenderedTile rendered = limited.renderTile(tableName, 25832, List.of(),
				List.of(), tile[0], tile[1], tile[2]);

		assertThat(rendered.truncated()).isTrue();

		Set<Long> ids = MvtTileDecoder.decode(rendered.mvt()).get(0).featureIds().stream()
				.map(Long::valueOf).collect(Collectors.toCollection(java.util.HashSet::new));
		assertThat(ids).hasSize(limit);

		long westCount = ids.stream().filter(id -> id <= perDistrict).count();
		long eastCount = ids.stream().filter(id -> id > perDistrict).count();

		// Expected under an unbiased scatter: perDistrict/(2*perDistrict) * limit = 2.500
		// each, standard deviation around 33 (hypergeometric, n=5.000 drawn from 40.000
		// split 20.000/20.000). The band below is roughly 15 standard deviations wide on
		// each side -- nowhere near flaky -- and still tight enough that the bug this
		// guards against (5.000 from one district, 0 from the other) fails it outright.
		assertThat(westCount)
				.as("Bezirk A (niedrige fid) darf nicht die ganze Kachel fuer sich haben")
				.isBetween(2000L, 3000L);
		assertThat(eastCount)
				.as("Bezirk B (hohe fid) darf nicht vollstaendig herausfallen")
				.isBetween(2000L, 3000L);
	}

	/**
	 * The same experiment as {@link #truncationSplitsAcrossSpatiallyClumpedInsertOrder()},
	 * with one change: every feature is an identical box instead of a point. Area
	 * and perimeter are equal for every one of them, so {@code ST_Area + ST_Length} ties
	 * across the whole table exactly as it does for points -- the tie is what matters, not
	 * the geometry type that produced it. If {@link MvtService#SPATIAL_SCATTER} were ever
	 * narrowed to special-case points (checking the geometry type, say, instead of just
	 * always being the next {@code ORDER BY} column after size), this is what would catch
	 * it going back to clumping by insertion order for every other uniform layer -- a
	 * parcel cadastre of same-sized lots, a raster grid, identically modelled buildings.
	 */
	@Test
	@DisplayName("a truncated tile of uniform-size polygons keeps both districts represented too, not just points")
	void uniformPolygonsClumpTheSameWayPointsDo() {
		String tableName = SqlIdentifier.tableName(UUID.randomUUID());
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();
		jdbc.sql("CREATE INDEX ON %s USING GIST (geom)".formatted(table)).update();

		// z=3's ~4.500 km tiles quantise to a 4096-unit grid, roughly 1,1 km per unit, so
		// the box needs to be several units wide to survive ST_AsMVTGeom's simplification
		// intact -- unlike a point, which is never degenerate at any zoom, a polygon far
		// below the pixel size collapses to nothing (that is what a first version of this
		// test, with 10x10 m boxes, found out the hard way: every feature vanished, and
		// the tile that renders "as empty" -- MvtService returning a null RenderedTile.mvt()
		// -- must not be read as "the fix failed", only as "the fixture was too small for
		// this zoom"). 5 km is comfortably clear of that floor.
		int boxSide = 5_000;
		int perDistrict = 5_000;
		jdbc.sql("""
				INSERT INTO %s (geom)
				SELECT ST_Multi(ST_MakeEnvelope(x0, y0, x0 + :side, y0 + :side, 25832))
				FROM (
				  SELECT 500000 + (random() * 15000) AS x0, 5900000 + (random() * 395000) AS y0
				  FROM generate_series(1, :count)
				) pts
				""".formatted(table))
				.param("side", boxSide)
				.param("count", perDistrict)
				.update();
		jdbc.sql("""
				INSERT INTO %s (geom)
				SELECT ST_Multi(ST_MakeEnvelope(x0, y0, x0 + :side, y0 + :side, 25832))
				FROM (
				  SELECT 700000 + (random() * 15000) AS x0, 5900000 + (random() * 395000) AS y0
				  FROM generate_series(1, :count)
				) pts
				""".formatted(table))
				.param("side", boxSide)
				.param("count", perDistrict)
				.update();
		jdbc.sql("ANALYZE " + table).update();

		int[] tile = tileCoveringBoth(jdbc);
		int limit = 2_000;

		long totalBeforeLimit = jdbc.sql("""
				SELECT count(*) FROM %s l,
				  (SELECT ST_Transform(ST_Segmentize(ST_TileEnvelope(:z, :x, :y), 100000), 25832) AS native) b
				WHERE l.geom && b.native
				""".formatted(table))
				.param("z", tile[0]).param("x", tile[1]).param("y", tile[2])
				.query(Long.class).single();
		assertThat(totalBeforeLimit)
				.as("Testvoraussetzung: die Kachel muss beide Bezirke vollstaendig sehen, vor jeder Kuerzung")
				.isEqualTo(2L * perDistrict);

		MvtService limited = new MvtService(jdbc, projectionDomain, limit);
		MvtService.RenderedTile rendered = limited.renderTile(tableName, 25832, List.of(),
				List.of(), tile[0], tile[1], tile[2]);

		assertThat(rendered.truncated()).isTrue();

		Set<Long> ids = MvtTileDecoder.decode(rendered.mvt()).get(0).featureIds().stream()
				.map(Long::valueOf).collect(Collectors.toCollection(java.util.HashSet::new));
		assertThat(ids).hasSize(limit);

		long westCount = ids.stream().filter(id -> id <= perDistrict).count();
		long eastCount = ids.stream().filter(id -> id > perDistrict).count();

		// Expected under an unbiased scatter: 1.000 each, standard deviation around 22
		// (hypergeometric, n=2.000 drawn from 10.000 split 5.000/5.000). The band is wide
		// enough to be flake-free and still fails outright on the old bug (2.000/0).
		assertThat(westCount)
				.as("Bezirk A (niedrige fid) darf nicht die ganze Kachel fuer sich haben")
				.isBetween(800L, 1200L);
		assertThat(eastCount)
				.as("Bezirk B (hohe fid) darf nicht vollstaendig herausfallen")
				.isBetween(800L, 1200L);
	}

	/**
	 * The zoom is deliberately coarse, not the zoom a client would pick for a district
	 * overview -- z=3's ~4.500 km tiles leave no doubt that a west/east split 220 km
	 * apart falls inside a single one, regardless of exactly where its grid lines land.
	 * A zoom picked only wide enough to "probably" work is what produced the review's
	 * own z=6 miss (class Javadoc); the SW corner still decides which cell, since that
	 * is the real production code path in {@link MvtService#nativeBounds}, but at this
	 * zoom it agrees with every other corner and the centroid, checked by hand for these
	 * exact coordinates before this test was written.
	 */
	private static int[] tileCoveringBoth(JdbcClient jdbc) {
		double[] lngLat = jdbc.sql("""
				SELECT ST_XMin(t) AS lng, ST_YMin(t) AS lat
				FROM (SELECT ST_Transform(ST_SetSRID(ST_MakeEnvelope(500000, 5900000, 720000, 6300000), 25832), 4326) AS t) s
				""").query((rs, rowNum) -> new double[] { rs.getDouble("lng"), rs.getDouble("lat") }).single();
		int zoom = 3;
		double latRad = Math.toRadians(lngLat[1]);
		int n = 1 << zoom;
		int x = (int) Math.floor((lngLat[0] + 180.0) / 360.0 * n);
		int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
		return new int[] { zoom, x, y };
	}
}
