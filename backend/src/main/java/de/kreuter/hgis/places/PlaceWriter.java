package de.kreuter.hgis.places;

import de.kreuter.hgis.common.Uuid7;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the whole {@code place} table in one go. CONTRACT.md is explicit about the
 * shape: "erst leeren, dann schreiben, in einer Transaktion" -- first empty, then write,
 * in one transaction -- so a client polling the refresh job never observes a half-emptied
 * table, and a request that comes in while a refresh is running still sees the previous,
 * complete copy right up until the new one commits.
 *
 * <p>One transaction for the whole write, unlike {@code ingest.ImportService}'s
 * batch-per-transaction approach: that split exists so a multi-gigabyte, minutes-long
 * import does not pin a connection and block autovacuum for the whole run. A Hamburg
 * refresh writes on the order of three hundred thousand small rows -- the write itself is
 * seconds, not minutes -- so nothing is gained by splitting it, and splitting it would
 * break the very guarantee CONTRACT.md asks for here.
 *
 * <p>Uses plain {@link JdbcTemplate} batching like {@code ingest.FeatureWriter}, for the
 * same reason: a real JDBC batch is what makes several thousand inserts per second
 * possible. Chunked into {@link #BATCH_SIZE} statements per {@code executeBatch}, again
 * like {@code FeatureWriter}, but for a reason that only appears at this table's new size:
 * a single batch holds every statement in the driver's own buffer until it is executed, so
 * handing it all 312329 rows at once would put a second copy of the whole extract in
 * memory next to the one the caller already holds. The chunks all run inside this one
 * transaction, so the all-or-nothing guarantee above is untouched.
 *
 * <p>Reprojection happens in PostGIS ({@code ST_Transform}), never in Java -- CONTRACT.md
 * is explicit about that too, and it is the same rule {@code FeatureWriter} and
 * {@code ingest.InspectionService} already follow.
 */
@Component
class PlaceWriter {

	/** Statements per {@code executeBatch}. The same 1000 {@code ingest.FeatureWriter} uses,
	 *  for the same reason: large enough that the per-round-trip cost disappears, small
	 *  enough that the driver's buffer stays a rounding error next to the data itself. */
	private static final int BATCH_SIZE = 1000;

	private static final String INSERT_SQL = """
			INSERT INTO gis_meta.place (id, name, context, kind, source, geom, fetched_at)
			VALUES (?, ?, ?, ?, 'hamburg', ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 25832), 4326), now())
			""";

	private final JdbcTemplate jdbcTemplate;

	PlaceWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** @return the number of rows written, {@code places.size()} */
	@Transactional
	int replaceAll(List<ParsedPlace> places) {
		jdbcTemplate.update("TRUNCATE TABLE gis_meta.place");

		for (int from = 0; from < places.size(); from += BATCH_SIZE) {
			writeBatch(places.subList(from, Math.min(from + BATCH_SIZE, places.size())));
		}
		return places.size();
	}

	private void writeBatch(List<ParsedPlace> batch) {
		jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				ParsedPlace place = batch.get(i);
				ps.setObject(1, Uuid7.generate());
				ps.setString(2, place.name());
				ps.setString(3, place.context());
				ps.setString(4, place.kind());
				ps.setDouble(5, place.x25832());
				ps.setDouble(6, place.y25832());
			}

			@Override
			public int getBatchSize() {
				return batch.size();
			}
		});
	}
}
