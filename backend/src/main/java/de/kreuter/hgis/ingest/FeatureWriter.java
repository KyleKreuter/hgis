package de.kreuter.hgis.ingest;

import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.TableCreator.ColumnMapping;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.locationtech.jts.io.WKBWriter;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Batch-inserts features into an already created layer table.
 *
 * Uses plain {@link JdbcTemplate} batching rather than the more ergonomic
 * {@code JdbcClient} used elsewhere: a real JDBC batch (addBatch/executeBatch) is what
 * makes a few thousand inserts per second possible, and that level of control isn't
 * what JdbcClient is for.
 *
 * Reprojection and multi-promotion happen entirely in PostGIS -- the geometry travels
 * as raw WKB bytes in its source SRID, and {@code ST_Multi(ST_Transform(...))} does the
 * rest. Every value, geometry included, is a bind parameter; nothing is ever
 * concatenated into the SQL text except identifiers that already passed through
 * {@link SqlIdentifier}.
 */
@Component
public class FeatureWriter {

	/** Caller is expected to chunk the feature stream into batches of this size. */
	public static final int BATCH_SIZE = 1000;

	private final JdbcTemplate jdbcTemplate;

	public FeatureWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Inserts one batch. Either all features in the batch are written, or the whole
	 * batch throws and nothing in it is committed -- the caller's transaction boundary
	 * decides what happens to batches that already succeeded earlier.
	 *
	 * @return number of rows written, always {@code features.size()} on success
	 */
	public int writeBatch(String tableName, List<ColumnMapping> columns, int sourceSrid, int targetSrid,
			List<SourceFeature> features) {
		if (features.isEmpty()) {
			return 0;
		}

		String sql = buildInsertSql(tableName, columns);
		WKBWriter wkbWriter = new WKBWriter(); // 2D only: layer geometry columns carry no Z/M

		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				SourceFeature feature = features.get(i);
				if (feature.geometry() == null) {
					// The reader contract promises null geometries are filtered out before
					// they ever reach a writer. Failing loudly here beats a cryptic
					// NOT NULL violation from Postgres if that promise is ever broken.
					throw new IllegalStateException("Feature with null geometry reached FeatureWriter");
				}

				ps.setBytes(1, wkbWriter.write(feature.geometry()));
				ps.setInt(2, sourceSrid);
				ps.setInt(3, targetSrid);

				int index = 4;
				for (ColumnMapping column : columns) {
					bindValue(ps, index++, feature.attributes().get(column.sourceName()));
				}
			}

			@Override
			public int getBatchSize() {
				return features.size();
			}
		});

		return features.size();
	}

	private static String buildInsertSql(String tableName, List<ColumnMapping> columns) {
		StringBuilder sql = new StringBuilder("INSERT INTO ")
				.append(SqlIdentifier.quoteLayerTable(tableName))
				.append(" (geom");
		for (ColumnMapping column : columns) {
			sql.append(", ").append(SqlIdentifier.quoteColumn(column.columnName()));
		}
		sql.append(") VALUES (ST_Multi(ST_Transform(ST_GeomFromWKB(?, ?), ?))");
		columns.forEach(c -> sql.append(", ?"));
		sql.append(")");
		return sql.toString();
	}

	/** Binds one attribute value, dispatching on its runtime type per {@code TypeMapper}'s
	 *  Java-type-to-column mapping. */
	private static void bindValue(PreparedStatement ps, int index, Object value) throws SQLException {
		switch (value) {
			case null -> ps.setNull(index, Types.NULL);
			case String s -> ps.setString(index, s);
			case Boolean b -> ps.setBoolean(index, b);
			case Integer i -> ps.setInt(index, i);
			case Short s -> ps.setInt(index, s);
			case Byte b -> ps.setInt(index, b);
			case Long l -> ps.setLong(index, l);
			case java.math.BigInteger bi -> ps.setLong(index, bi.longValueExact());
			case Float f -> ps.setDouble(index, f);
			case Double d -> ps.setDouble(index, d);
			case java.math.BigDecimal bd -> ps.setBigDecimal(index, bd);
			// java.sql.Date/Time/Timestamp all extend java.util.Date and must be matched
			// before it, or every one of them would fall through to the generic case.
			case java.sql.Date d -> ps.setDate(index, d);
			case java.sql.Time t -> ps.setTime(index, t);
			case java.sql.Timestamp t -> ps.setTimestamp(index, t);
			case java.util.Date d -> ps.setTimestamp(index, new java.sql.Timestamp(d.getTime()));
			case java.time.Instant instant -> ps.setTimestamp(index, java.sql.Timestamp.from(instant));
			case byte[] bytes -> ps.setBytes(index, bytes);
			case java.util.UUID uuid -> ps.setObject(index, uuid);
			default -> ps.setString(index, value.toString());
		}
	}
}
