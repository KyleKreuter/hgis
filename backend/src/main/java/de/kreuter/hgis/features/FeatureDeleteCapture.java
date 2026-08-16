package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletes a batch of rows and, in the same statement, captures what they looked like the
 * moment before -- geometry as GeoJSON in EPSG:4326, attributes keyed by column_name, the
 * same shape an {@code EditDtos.Create} arrives in.
 *
 * <p>This is the fallback CONTRACT.md "Schreibstufe" names for a deleted feature: a
 * deleted object gets no trash of its own the way a whole layer does, so the change log's
 * captured row is the only place its shape survives. Shared by every write path that can
 * make a feature disappear -- {@link EditService}'s batch delete and
 * {@link SplitMergeService#merge}, which drops every part but the lead into the same
 * union -- so the capture, and the guarantee that nothing slips through a gap between two
 * statements, lives in exactly one place.
 */
@Component
class FeatureDeleteCapture {

	/** @param count how many rows were actually removed
	 *  @param rowsJson their captured shape, or null if none were removed */
	record Result(int count, String rowsJson) {
	}

	private final JdbcClient jdbc;

	FeatureDeleteCapture(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Deletes the given rows and captures them in the same statement.
	 *
	 * <p>One statement, not a {@code SELECT} followed by the {@code DELETE}: a {@code
	 * DELETE ... RETURNING} inside a CTE hands the removed rows to the aggregate directly,
	 * so there is no window in which a concurrent write could see, or change, a row this
	 * statement is about to remove.
	 *
	 * @param table already through {@link SqlIdentifier#quoteLayerTable}
	 * @param fields the layer's fields, in any order -- only the column names are used
	 */
	Result deleteAndCapture(String table, Collection<LayerField> fields, List<Long> fids) {
		StringBuilder returning = new StringBuilder("fid, geom");
		StringBuilder properties = new StringBuilder("jsonb_build_object(");
		boolean first = true;
		for (LayerField field : fields) {
			String column = SqlIdentifier.quoteColumn(field.getColumnName());
			returning.append(", ").append(column);
			if (!first) {
				properties.append(", ");
			}
			// The column name is a validated SQL identifier (SqlIdentifier.quoteColumn
			// above already rejected anything unsafe), so it can never carry a quote of
			// its own -- embedding it as a string literal key needs no escaping.
			properties.append('\'').append(field.getColumnName()).append("', ").append(column);
			first = false;
		}
		properties.append(')');

		String sql = """
				WITH removed AS (
				    DELETE FROM %s WHERE fid = ANY(:fids)
				    RETURNING %s
				)
				SELECT count(*) AS deleted_count,
				       jsonb_agg(jsonb_build_object(
				           'fid', fid,
				           'geometry', ST_AsGeoJSON(ST_Transform(geom, 4326))::jsonb,
				           'properties', %s
				       ))::text AS deleted_rows
				FROM removed
				""".formatted(table, returning, properties);

		Map<String, Object> row = jdbc.sql(sql)
				.param("fids", fids.toArray(Long[]::new))
				.query()
				.singleRow();

		int count = ((Number) row.get("deleted_count")).intValue();
		String rowsJson = (String) row.get("deleted_rows");
		return new Result(count, rowsJson);
	}
}
