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
 * refresh writes on the order of ten thousand small rows -- the write itself is a few
 * seconds, not minutes -- so nothing is gained by splitting it, and splitting it would
 * break the very guarantee CONTRACT.md asks for here.
 *
 * <p>Uses plain {@link JdbcTemplate} batching like {@code ingest.FeatureWriter}, for the
 * same reason: a real JDBC batch is what makes several thousand inserts per second
 * possible. Reprojection happens in PostGIS ({@code ST_Transform}), never in Java --
 * CONTRACT.md is explicit about that too, and it is the same rule {@code FeatureWriter}
 * and {@code ingest.InspectionService} already follow.
 */
@Component
class PlaceWriter {

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
		if (places.isEmpty()) {
			return 0;
		}

		jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				ParsedPlace place = places.get(i);
				ps.setObject(1, Uuid7.generate());
				ps.setString(2, place.name());
				ps.setString(3, place.context());
				ps.setString(4, place.kind());
				ps.setDouble(5, place.x25832());
				ps.setDouble(6, place.y25832());
			}

			@Override
			public int getBatchSize() {
				return places.size();
			}
		});
		return places.size();
	}
}
