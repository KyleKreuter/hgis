package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.dto.FeatureDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads rows out of a layer's payload table.
 *
 * <p>Paging is keyset, not {@code OFFSET}. An offset does not skip rows, it produces and
 * discards them, so scrolling deep into a large layer gets slower the further it goes --
 * at row 500.000 the database builds half a million rows to throw away. A keyset asks
 * "everything after this position", which stays one index seek no matter how far in.
 *
 * <p>The price is that pages can only be walked in order, which is exactly how a
 * scrolling table reads them.
 */
@Service
public class FeatureQueryService {

	/** Guard against a client asking for an entire layer in one response. */
	private static final int MAX_PAGE_SIZE = 1000;

	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final JdbcClient jdbc;

	FeatureQueryService(LayerRepository layerRepository, LayerFieldRepository fieldRepository,
			JdbcClient jdbc) {
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.jdbc = jdbc;
	}

	/** Everything the client may vary about a feature query. */
	public record Query(
			String sort,
			boolean descending,
			String filter,
			double[] bbox,
			boolean includeGeometry,
			String cursor,
			int size) {
	}

	@Transactional(readOnly = true)
	public FeatureDtos.Page list(UUID layerId, Query query) {
		Layer layer = require(layerId);
		List<LayerField> fields = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId);
		String table = SqlIdentifier.quoteLayerTable(layer.getTableName());

		LayerField sortField = resolveSortField(query.sort(), fields);
		Map<String, Object> parameters = new LinkedHashMap<>();
		String where = buildWhere(query, layer, fields, parameters);

		// Fetching one extra row is what answers "is there more" without a second query
		// and without counting: if the extra row shows up, there is a next page.
		int size = Math.clamp(query.size(), 1, MAX_PAGE_SIZE);
		String sql = "SELECT " + selectList(fields, query.includeGeometry())
				+ " FROM " + table + " f"
				+ where
				+ " ORDER BY " + orderBy(sortField, query.descending())
				+ " LIMIT " + (size + 1);

		var statement = jdbc.sql(sql);
		for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
			statement = statement.param(parameter.getKey(), parameter.getValue());
		}
		List<Map<String, Object>> rows = statement.query().listOfRows();

		boolean hasMore = rows.size() > size;
		List<Map<String, Object>> page = hasMore ? rows.subList(0, size) : rows;

		String nextCursor = null;
		if (hasMore) {
			Map<String, Object> last = page.get(page.size() - 1);
			Object sortValue = sortField == null ? null : last.get(sortField.getColumnName());
			nextCursor = new FeatureCursor(sortValue, ((Number) last.get("fid")).longValue()).encode();
		}

		// Only the first page carries the total. Repeating the count on every page would
		// re-scan the table for a number that cannot have changed within one filter.
		Long total = query.cursor() == null ? count(table, where, parameters) : null;

		return new FeatureDtos.Page(page.stream().map(row -> toFeature(row, fields)).toList(),
				nextCursor, total);
	}

	/** One feature with everything it has -- what Identify shows for a clicked geometry. */
	@Transactional(readOnly = true)
	public FeatureDtos.Feature get(UUID layerId, long fid) {
		Layer layer = require(layerId);
		List<LayerField> fields = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId);

		List<Map<String, Object>> rows = jdbc.sql("SELECT " + selectList(fields, true)
						+ " FROM " + SqlIdentifier.quoteLayerTable(layer.getTableName()) + " f"
						+ " WHERE f.fid = :fid")
				.param("fid", fid)
				.query()
				.listOfRows();

		if (rows.isEmpty()) {
			throw new NotFoundException("Objekt " + fid + " existiert im Layer " + layerId + " nicht");
		}
		return toFeature(rows.get(0), fields);
	}

	// --- query building -------------------------------------------------------------

	private String selectList(List<LayerField> fields, boolean includeGeometry) {
		List<String> columns = new ArrayList<>();
		columns.add("f.fid");
		// xmin is a system column: the transaction that last wrote this row. Cast to text
		// because it is an xid, which JDBC has no type for.
		columns.add("f.xmin::text AS row_version");
		for (LayerField field : fields) {
			columns.add("f." + SqlIdentifier.quoteColumn(field.getColumnName()));
		}
		if (includeGeometry) {
			// Full precision on purpose. Tile geometry is quantised to the tile grid and
			// simplified, which is close enough to look right and wrong enough to snap
			// to (plan section D.1) -- this endpoint is the exact source.
			columns.add("ST_AsGeoJSON(ST_Transform(f.geom, 4326)) AS geometry");
		}
		return String.join(", ", columns);
	}

	private String buildWhere(Query query, Layer layer, List<LayerField> fields,
			Map<String, Object> parameters) {
		List<String> conditions = new ArrayList<>();

		if (query.bbox() != null) {
			double[] bbox = query.bbox();
			if (bbox.length != 4) {
				throw new BadRequestException("bbox erwartet vier Werte: minLng,minLat,maxLng,maxLat");
			}
			// The envelope is transformed into the layer's CRS, never geom into 4326 --
			// same reasoning as the tile query: only an untransformed geom column can use
			// its GiST index.
			conditions.add("f.geom && ST_Transform("
					+ "ST_MakeEnvelope(:bboxMinX, :bboxMinY, :bboxMaxX, :bboxMaxY, 4326), :layerSrid)");
			parameters.put("bboxMinX", bbox[0]);
			parameters.put("bboxMinY", bbox[1]);
			parameters.put("bboxMaxX", bbox[2]);
			parameters.put("bboxMaxY", bbox[3]);
			parameters.put("layerSrid", layer.getSrid());
		}

		FilterParser.ParsedFilter filter = FilterParser.parse(query.filter(), fields);
		if (filter != null) {
			conditions.add(filter.sql());
			parameters.putAll(filter.parameters());
		}

		if (query.cursor() != null) {
			LayerField sortField = resolveSortField(query.sort(), fields);
			FeatureCursor cursor = FeatureCursor.decode(query.cursor());
			conditions.add(keysetCondition(sortField, query.descending(), cursor, parameters));
		}

		return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
	}

	/**
	 * "Everything strictly after the cursor" in the requested ordering.
	 *
	 * <p>Both directions sort NULLS LAST, which makes the two cases symmetric: sitting on
	 * a NULL means being in the trailing block where only the fid still moves, and sitting
	 * on a value means taking everything beyond it plus the NULLs still to come.
	 */
	private String keysetCondition(LayerField sortField, boolean descending, FeatureCursor cursor,
			Map<String, Object> parameters) {
		parameters.put("cursorFid", cursor.fid());
		if (sortField == null) {
			return "f.fid > :cursorFid";
		}

		String column = "f." + SqlIdentifier.quoteColumn(sortField.getColumnName());
		if (cursor.sortValue() == null) {
			return "(" + column + " IS NULL AND f.fid > :cursorFid)";
		}

		parameters.put("cursorValue", cursor.sortValue());
		String bound = castedCursorValue(sortField);
		String beyond = descending ? "<" : ">";
		return "(" + column + " " + beyond + " " + bound
				+ " OR " + column + " IS NULL"
				+ " OR (" + column + " = " + bound + " AND f.fid > :cursorFid))";
	}

	/**
	 * A cursor value travels as JSON and comes back as a string or a double, so a date
	 * column needs the cast spelled out -- otherwise the comparison would be lexical.
	 * The type comes from our own TypeMapper, never from the client.
	 */
	private String castedCursorValue(LayerField sortField) {
		String type = sortField.getDataType().toLowerCase(Locale.ROOT);
		if (type.equals("date")) {
			return "CAST(:cursorValue AS date)";
		}
		if (type.startsWith("timestamp")) {
			return "CAST(:cursorValue AS timestamptz)";
		}
		return ":cursorValue";
	}

	private String orderBy(LayerField sortField, boolean descending) {
		// fid last and always ascending: it is the tie-breaker that makes the ordering
		// total. Without it, rows sharing a sort value could come back in a different
		// order per page and the keyset would skip or repeat them.
		if (sortField == null) {
			return "f.fid ASC";
		}
		return "f." + SqlIdentifier.quoteColumn(sortField.getColumnName())
				+ (descending ? " DESC" : " ASC") + " NULLS LAST, f.fid ASC";
	}

	/**
	 * How many rows the current filter matches in total.
	 *
	 * <p>Only called for the first page, which is also what makes it correct: without a
	 * cursor the WHERE clause carries no keyset predicate, so this counts the whole
	 * filtered set rather than what happens to be left after the current position.
	 */
	private Long count(String table, String where, Map<String, Object> parameters) {
		var statement = jdbc.sql("SELECT COUNT(*) FROM " + table + " f" + where);
		for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
			statement = statement.param(parameter.getKey(), parameter.getValue());
		}
		return statement.query(Long.class).single();
	}

	private FeatureDtos.Feature toFeature(Map<String, Object> row, List<LayerField> fields) {
		Map<String, Object> properties = new LinkedHashMap<>();
		for (LayerField field : fields) {
			properties.put(field.getColumnName(), row.get(field.getColumnName()));
		}
		return new FeatureDtos.Feature(
				((Number) row.get("fid")).longValue(),
				(String) row.get("row_version"),
				properties,
				(String) row.get("geometry"));
	}

	private LayerField resolveSortField(String sort, List<LayerField> fields) {
		if (sort == null || sort.isBlank() || sort.equalsIgnoreCase("fid")) {
			return null;
		}
		return fields.stream()
				.filter(field -> field.getSourceName().equalsIgnoreCase(sort)
						|| field.getColumnName().equalsIgnoreCase(sort))
				.findFirst()
				.orElseThrow(() -> new BadRequestException("Unbekanntes Sortierfeld: " + sort));
	}

	private Layer require(UUID layerId) {
		return layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));
	}
}
