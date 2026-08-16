package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.ProjectionDomain;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.dto.FeatureDtos;
import java.time.LocalDate;
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

	/**
	 * Upper bound for {@link #fids}. Mirrors {@code FidSelection.MAX_FIDS} in the export
	 * package -- the fid endpoint and the export exist precisely so a client can turn one
	 * into the other, so their ceilings must agree. Kept as its own constant rather than a
	 * dependency on the export package for one number: features has no other reason to
	 * import from export, and a duplicated, commented constant is cheaper than that coupling.
	 */
	private static final int MAX_FIDS = 100_000;

	/**
	 * How finely a bbox is sampled before it is transformed into the layer's CRS, in
	 * degrees. Small enough to follow the curve a projection puts into a straight
	 * lng/lat line, large enough to leave a city-sized rectangle at its four corners.
	 */
	private static final int SEGMENT_DEGREES = 1;

	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final ProjectionDomain projectionDomain;
	private final JdbcClient jdbc;

	FeatureQueryService(LayerRepository layerRepository, LayerFieldRepository fieldRepository,
			ProjectionDomain projectionDomain, JdbcClient jdbc) {
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.projectionDomain = projectionDomain;
		this.jdbc = jdbc;
	}

	/** Everything the client may vary about a feature query. */
	public record Query(
			String sort,
			boolean descending,
			String filter,
			String search,
			double[] bbox,
			String mode,
			boolean includeGeometry,
			String cursor,
			int size) {
	}

	/**
	 * The exact geometry test layered on top of the index-backed {@code &&} prefilter.
	 *
	 * <p>{@code &&} only compares bounding boxes, so a diagonal or L-shaped geometry whose
	 * envelope overlaps the query rectangle can still miss it entirely -- both modes need
	 * the exact test, {@code INTERSECTS} included.
	 */
	private enum SelectionMode { INTERSECTS, CONTAINS }

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
		int size = requirePageSize(query.size());
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

	/**
	 * The full fid set a filter/search restriction matches -- no geometry, no paging.
	 *
	 * <p>{@link #list} only ever exposes one page through its cursor; this is what lets a
	 * client turn a restriction into the complete fid list the selection store -- and
	 * through it the existing export -- already knows how to work with (CONTRACT.md phase
	 * 14). Ordered by fid so the response is stable across repeated calls.
	 *
	 * @throws BadRequestException when the restriction matches more than {@link #MAX_FIDS}
	 *     objects, naming the actual count
	 */
	@Transactional(readOnly = true)
	public FeatureDtos.FidsResponse fids(UUID layerId, String filter, String search) {
		Layer layer = require(layerId);
		List<LayerField> fields = fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId);
		String table = SqlIdentifier.quoteLayerTable(layer.getTableName());

		Map<String, Object> parameters = new LinkedHashMap<>();
		// bbox, mode, cursor and sort play no role here -- only filter and search restrict
		// the fid set -- so the Query passed into buildWhere carries only those two and
		// leaves the rest at their default.
		Query restriction = new Query(null, false, filter, search, null, null, false, null, 0);
		String where = buildWhere(restriction, layer, fields, parameters);

		long total = count(table, where, parameters);
		if (total > MAX_FIDS) {
			throw new BadRequestException("Die Einschränkung trifft " + total
					+ " Objekte. Erlaubt sind höchstens " + MAX_FIDS + ".");
		}

		var statement = jdbc.sql("SELECT f.fid FROM " + table + " f" + where + " ORDER BY f.fid ASC");
		for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
			statement = statement.param(parameter.getKey(), parameter.getValue());
		}
		List<Long> fids = statement.query(Long.class).list();

		return new FeatureDtos.FidsResponse(fids, fids.size());
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

		// Resolved unconditionally so an unknown mode is rejected even without a bbox,
		// rather than being silently ignored.
		SelectionMode mode = resolveSelectionMode(query.mode());

		if (query.bbox() != null) {
			double[] bbox = query.bbox();
			if (bbox.length != 4) {
				throw new BadRequestException("bbox erwartet vier Werte: minLng,minLat,maxLng,maxLat");
			}
			parameters.put("bboxMinX", bbox[0]);
			parameters.put("bboxMinY", bbox[1]);
			parameters.put("bboxMaxX", bbox[2]);
			parameters.put("bboxMaxY", bbox[3]);

			String rectangle = "ST_MakeEnvelope(:bboxMinX, :bboxMinY, :bboxMaxX, :bboxMaxY, 4326)";
			String envelope;
			String geom;
			if (projectionDomain.covers(layer.getSrid(), bbox[0], bbox[1], bbox[2], bbox[3])) {
				// The envelope is transformed into the layer's CRS, never geom into 4326 --
				// same reasoning as the tile query: only an untransformed geom column can use
				// its GiST index. ST_Segmentize first, because ST_Transform moves the four
				// corners and leaves the curve between them to be guessed: for anything wider
				// than a city the box around those corners is narrower than the rectangle
				// really covers, and every row in the difference would go missing.
				envelope = "ST_Transform(ST_Segmentize(" + rectangle + ", " + SEGMENT_DEGREES + "), :layerSrid)";
				geom = "f.geom";
				parameters.put("layerSrid", layer.getSrid());
			}
			else {
				// The rectangle reaches past what the layer's CRS can describe, so the sides
				// swap: the geometry is transformed instead. That gives up the GiST index on
				// geom and reads the whole table -- the price of an answer at all. Projecting
				// it anyway is what produced the reported bug: a bbox of the whole world
				// folded UTM32's ±180° back onto its central meridian and became a rectangle
				// of zero width, so the layer that holds 229.876 objects reported none.
				envelope = rectangle;
				geom = "ST_Transform(f.geom, 4326)";
			}

			conditions.add(geom + " && " + envelope);

			// && alone only narrows by bounding box; these add the precise test the
			// client asked for, on top of it -- not instead of it (see SelectionMode).
			if (mode == SelectionMode.INTERSECTS) {
				conditions.add("ST_Intersects(" + geom + ", " + envelope + ")");
			}
			else if (mode == SelectionMode.CONTAINS) {
				conditions.add("ST_Contains(" + envelope + ", " + geom + ")");
			}
		}

		FilterParser.ParsedFilter filter = FilterParser.parse(query.filter(), fields);
		if (filter != null) {
			conditions.add(filter.sql());
			parameters.putAll(filter.parameters());
		}

		// Combined with filter by AND (and with everything else in `conditions`), not OR:
		// the two are separate refinements of the same result set, not alternatives.
		FilterParser.ParsedFilter search = TextSearch.parse(query.search(), fields);
		if (search != null) {
			conditions.add(search.sql());
			parameters.putAll(search.parameters());
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
			// Sorting by fid alone still has a direction: "newest first" is a fid sorted
			// descending, and ignoring desc here would silently serve the opposite.
			return descending ? "f.fid < :cursorFid" : "f.fid > :cursorFid";
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
	 * A cursor value travels as JSON and comes back as a string or a number, so a column
	 * whose type has no string form of its own needs the cast spelled out.
	 *
	 * <p>For {@code date} and {@code timestamp} that is a matter of meaning: without the
	 * cast the comparison would be lexical, and "09.05." would sort before "10.01.". For
	 * {@code time}, {@code uuid} and {@code bytea} it is a matter of the query running at
	 * all -- PostgreSQL has no operator between those types and the {@code varchar} a bound
	 * string arrives as, so the page after the first one came back as a 500. All three are
	 * reachable: {@code time} is one of the nine types a field can be created with, the
	 * other two come out of an import.
	 *
	 * <p>{@code numeric} is here for a third reason: it is exact, and JSON has no number
	 * that is. {@link FeatureCursor} therefore sends its digits as text, and this is where
	 * they become a number again -- comparing them as text would put "10" before "9".
	 *
	 * <p>The type comes from our own TypeMapper, never from the client.
	 */
	private String castedCursorValue(LayerField sortField) {
		String type = sortField.getDataType().toLowerCase(Locale.ROOT);
		if (type.startsWith("timestamp")) {
			return "CAST(:cursorValue AS timestamptz)";
		}
		return switch (type) {
			case "date", "time", "uuid", "bytea", "numeric" -> "CAST(:cursorValue AS " + type + ")";
			default -> ":cursorValue";
		};
	}

	private String orderBy(LayerField sortField, boolean descending) {
		// As a tie-breaker fid is always ascending: it is what makes the ordering total,
		// and rows sharing a sort value would otherwise come back in a different order per
		// page, which is exactly what makes a keyset skip or repeat them. Sorted on its
		// own, though, fid is the sort column and follows the requested direction.
		if (sortField == null) {
			return descending ? "f.fid DESC" : "f.fid ASC";
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
			properties.put(field.getColumnName(), toWireValue(row.get(field.getColumnName()), field));
		}
		return new FeatureDtos.Feature(
				((Number) row.get("fid")).longValue(),
				(String) row.get("row_version"),
				properties,
				(String) row.get("geometry"));
	}

	/**
	 * The driver hands a {@code date} column back as {@link java.sql.Date}, a
	 * {@link java.util.Date} subtype Jackson serialises with its default instant
	 * handling: midnight in the JVM's default zone, converted to UTC. On a positive UTC
	 * offset that prints the calendar day *before* the one actually stored. Converting to
	 * {@link LocalDate} keeps only the calendar date on the wire -- "2024-03-01", the same
	 * shape {@code GeoJsonExportService} already gets for free from {@code to_jsonb()}.
	 *
	 * <p>Every other column type already reads back correctly as-is: {@code timestamptz}
	 * carries an absolute instant, so {@link java.sql.Timestamp}'s default serialisation
	 * does not depend on the server's zone the way {@code java.sql.Date} does, and
	 * {@code uuid} / {@code bytea} already arrive as {@link java.util.UUID} / {@code byte[]},
	 * which Jackson serialises as a plain string on its own.
	 */
	private static Object toWireValue(Object value, LayerField field) {
		if (value instanceof java.sql.Date sqlDate && "date".equalsIgnoreCase(field.getDataType())) {
			return sqlDate.toLocalDate();
		}
		return value;
	}

	/**
	 * The page size, or a rejection.
	 *
	 * <p>Clamping is what this used to do, and it answered a request for 5.000 rows with
	 * 1.000 and nothing that said so. A person might stop at a number that looks too round;
	 * a program takes the page for the whole answer and computes on a fifth of it. The
	 * ceiling itself is right and stays -- only its silence is gone.
	 *
	 * @throws BadRequestException when the size is outside 1..{@link #MAX_PAGE_SIZE}
	 */
	private static int requirePageSize(int size) {
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new BadRequestException("size muss zwischen 1 und " + MAX_PAGE_SIZE
					+ " liegen. Angefragt waren " + size + ".");
		}
		return size;
	}

	/**
	 * The field to sort by, or null for the fid alone.
	 *
	 * <p>The rule lives in {@link QueryFields}, shared with the filter expression: this and
	 * {@link FilterParser} used to resolve the same name to different fields whenever one
	 * field's display name was another's column name, so a filter and a sort written with
	 * the same word read different columns without saying so.
	 *
	 * <p>Still reports "Unbekanntes Sortierfeld", which {@code frontend/src/table/
	 * sortValidity.ts} matches on to fall back to unsorted when the field was deleted.
	 */
	private LayerField resolveSortField(String sort, List<LayerField> fields) {
		return QueryFields.requireSortField(sort, fields);
	}

	/**
	 * No Spring enum binding on the {@code @RequestParam}: that would throw a generic 400
	 * on a bad value, without the German message every other validation error in this
	 * class gives.
	 */
	private SelectionMode resolveSelectionMode(String mode) {
		if (mode == null) {
			return null;
		}
		return switch (mode) {
			case "intersects" -> SelectionMode.INTERSECTS;
			case "contains" -> SelectionMode.CONTAINS;
			default -> throw new BadRequestException("Unbekannter Auswahlmodus: " + mode);
		};
	}

	/** Every caller here reads the payload table, so a map image (kind WMS) is rejected up front. */
	private Layer require(UUID layerId) {
		Layer layer = layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));
		layer.requireVector();
		return layer;
	}
}
