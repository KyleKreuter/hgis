package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.ConflictException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.dto.EditDtos;
import de.kreuter.hgis.features.dto.FeatureDtos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Applies a batch of edits to one layer, all inside a single transaction.
 *
 * <p>Geometry travels as GeoJSON in EPSG:4326 and is converted, reprojected and promoted
 * to its multi-type inside PostGIS -- the same {@code ST_Multi(ST_Transform(...))} the
 * import uses, so a polygon drawn by hand and a polygon read from a shapefile end up
 * stored identically.
 */
@Service
public class EditService {

	/** Guard against a single request trying to rewrite a whole layer. */
	private static final int MAX_BATCH = 5000;

	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final JdbcClient jdbc;
	private final LayerBookkeeping bookkeeping;

	EditService(LayerRepository layerRepository, LayerFieldRepository fieldRepository,
			JdbcClient jdbc, LayerBookkeeping bookkeeping) {
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.jdbc = jdbc;
		this.bookkeeping = bookkeeping;
	}

	@Transactional
	public EditDtos.Response apply(UUID layerId, EditDtos.Request request) {
		Layer layer = layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));

		int total = request.creates().size() + request.updates().size() + request.deletes().size();
		if (total == 0) {
			throw new BadRequestException("Das Änderungspaket ist leer");
		}
		if (total > MAX_BATCH) {
			throw new BadRequestException(
					"Das Änderungspaket umfasst " + total + " Änderungen. Erlaubt sind " + MAX_BATCH + ".");
		}

		String table = SqlIdentifier.quoteLayerTable(layer.getTableName());
		Map<String, LayerField> fields = fieldsByColumn(layerId);

		Map<Long, Long> createdFids = new LinkedHashMap<>();
		for (EditDtos.Create create : request.creates()) {
			createdFids.put(create.clientId(), insert(table, layer, fields, create, request.repairsInvalid()));
		}

		int updated = 0;
		for (EditDtos.Update update : request.updates()) {
			updated += update(table, layer, fields, update, request.repairsInvalid());
		}

		int deleted = request.deletes().isEmpty() ? 0
				: jdbc.sql("DELETE FROM " + table + " WHERE fid = ANY(:fids)")
						.param("fids", request.deletes().toArray(Long[]::new))
						.update();

		return finish(layer, table, createdFids, updated, deleted);
	}

	// --- writes -----------------------------------------------------------------------

	private long insert(String table, Layer layer, Map<String, LayerField> fields,
			EditDtos.Create create, boolean repairInvalid) {
		if (create.geometry() == null) {
			throw new BadRequestException("Neues Objekt " + create.clientId() + " hat keine Geometrie");
		}
		String geometrySql = geometryExpression(layer, create.geometry(), repairInvalid, "g");

		List<String> columns = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		collectProperties(fields, create.properties(), columns, values);

		StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (geom");
		columns.forEach(column -> sql.append(", ").append(column));
		sql.append(") VALUES (").append(geometrySql);
		for (int i = 0; i < columns.size(); i++) {
			sql.append(", :v").append(i);
		}
		sql.append(") RETURNING fid");

		var statement = jdbc.sql(sql.toString()).param("g", create.geometry().toString());
		for (int i = 0; i < values.size(); i++) {
			statement = statement.param("v" + i, values.get(i));
		}
		return statement.query(Long.class).single();
	}

	private int update(String table, Layer layer, Map<String, LayerField> fields,
			EditDtos.Update update, boolean repairInvalid) {
		List<String> assignments = new ArrayList<>();
		List<Object> values = new ArrayList<>();

		if (update.geometry() != null) {
			assignments.add("geom = " + geometryExpression(layer, update.geometry(), repairInvalid, "g"));
		}
		if (update.properties() != null) {
			List<String> columns = new ArrayList<>();
			collectProperties(fields, update.properties(), columns, values);
			for (int i = 0; i < columns.size(); i++) {
				assignments.add(columns.get(i) + " = :v" + i);
			}
		}
		if (assignments.isEmpty()) {
			throw new BadRequestException(
					"Änderung an Objekt " + update.fid() + " enthält weder Geometrie noch Feldwerte");
		}

		// xmin is the transaction that last wrote the row -- PostgreSQL's own row version,
		// which is why optimistic locking here needs no extra column (plan section D.7).
		StringBuilder sql = new StringBuilder("UPDATE ").append(table)
				.append(" SET ").append(String.join(", ", assignments))
				.append(" WHERE fid = :fid");
		if (update.rowVersion() != null) {
			sql.append(" AND xmin::text = :rowVersion");
		}

		var statement = jdbc.sql(sql.toString()).param("fid", update.fid());
		if (update.geometry() != null) {
			statement = statement.param("g", update.geometry().toString());
		}
		if (update.rowVersion() != null) {
			statement = statement.param("rowVersion", update.rowVersion());
		}
		for (int i = 0; i < values.size(); i++) {
			statement = statement.param("v" + i, values.get(i));
		}

		int affected = statement.update();
		if (affected == 0) {
			throw conflictOrMissing(table, update.fid());
		}
		return affected;
	}

	/**
	 * Zero rows updated has two very different causes, and the client needs to tell them
	 * apart: the row is gone, or someone else changed it. Re-reading is the only way to
	 * know which.
	 */
	private RuntimeException conflictOrMissing(String table, long fid) {
		List<Map<String, Object>> rows = jdbc
				.sql("SELECT fid, xmin::text AS row_version FROM " + table + " WHERE fid = :fid")
				.param("fid", fid)
				.query()
				.listOfRows();

		if (rows.isEmpty()) {
			return new NotFoundException("Objekt " + fid + " existiert nicht mehr");
		}
		return new ConflictException(
				"Eine andere Stelle hat Objekt " + fid + " zwischenzeitlich geändert",
				rows.get(0));
	}

	// --- geometry ---------------------------------------------------------------------

	/**
	 * SQL expression that turns the client's GeoJSON into a storable geometry, validating
	 * it on the way.
	 *
	 * <p>Validation runs in Java rather than as part of the statement so a violation can
	 * be reported with its reason and location. Letting the geometry column's constraint
	 * catch it would produce a database error naming neither.
	 */
	private String geometryExpression(Layer layer, JsonNode geometry, boolean repairInvalid,
			String parameter) {
		String geoJson = geometry.toString();
		validate(geoJson, layer, repairInvalid);

		String source = repairInvalid
				? "ST_CollectionExtract(ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON(:%s), 4326)), %d)"
						.formatted(parameter, dimensionOf(layer))
				: "ST_SetSRID(ST_GeomFromGeoJSON(:%s), 4326)".formatted(parameter);

		return "ST_Multi(ST_Transform(" + source + ", " + layer.getSrid() + "))";
	}

	private void validate(String geoJson, Layer layer, boolean repairInvalid) {
		Map<String, Object> detail;
		try {
			detail = jdbc.sql("""
					SELECT d.valid,
					       d.reason,
					       ST_X(d.location) AS x,
					       ST_Y(d.location) AS y,
					       GeometryType(g.geom) AS geometry_type
					FROM (SELECT ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326) AS geom) g,
					     LATERAL ST_IsValidDetail(g.geom) AS d(valid, reason, location)
					""")
					.param("g", geoJson)
					.query()
					.singleRow();
		}
		catch (RuntimeException ex) {
			// Malformed GeoJSON never reaches ST_IsValidDetail; PostGIS rejects it while
			// parsing, and the raw message is more useful than anything generic.
			throw new BadRequestException("Das Programm kann die Geometrie nicht lesen: " + rootMessage(ex));
		}

		requireCompatibleType(layer, (String) detail.get("geometry_type"));

		if (Boolean.TRUE.equals(detail.get("valid")) || repairInvalid) {
			return;
		}

		// Deliberately not repaired here. ST_MakeValid changes the shape -- and can turn a
		// polygon into a GeometryCollection that no longer fits the column. Which is worth
		// more, the drawn shape or a stored one, is the user's call, not ours.
		String reason = (String) detail.get("reason");
		Object x = detail.get("x");
		Object y = detail.get("y");
		String where = (x == null || y == null) ? ""
				: " bei %.6f, %.6f".formatted(((Number) x).doubleValue(), ((Number) y).doubleValue());

		throw new BadRequestException("Ungültige Geometrie: " + reason + where
				+ ". Lassen Sie sie automatisch reparieren oder korrigieren Sie sie von Hand.");
	}

	/**
	 * A layer column typed MultiPolygon cannot hold a line, and finding that out from a
	 * constraint violation tells the user nothing they can act on.
	 */
	private void requireCompatibleType(Layer layer, String actualType) {
		if (actualType == null || layer.getGeometryType().equals("GEOMETRY")) {
			return;
		}
		String expected = layer.getGeometryType().toUpperCase(Locale.ROOT);
		String actual = actualType.toUpperCase(Locale.ROOT);
		// ST_Multi promotes on write, so the single-part form is just as acceptable.
		if (expected.equals(actual) || expected.equals("MULTI" + actual)) {
			return;
		}
		throw new BadRequestException("Der Layer nimmt " + humanType(expected)
				+ " auf. Die Geometrie ist " + humanType(actual) + ".");
	}

	private static String humanType(String geometryType) {
		return switch (geometryType) {
			case "POINT", "MULTIPOINT" -> "Punkte";
			case "LINESTRING", "MULTILINESTRING" -> "Linien";
			case "POLYGON", "MULTIPOLYGON" -> "Flächen";
			default -> geometryType;
		};
	}

	/** Dimension ST_CollectionExtract keeps: 1 point, 2 line, 3 area. */
	private static int dimensionOf(Layer layer) {
		return switch (layer.getGeometryType()) {
			case "MULTIPOINT" -> 1;
			case "MULTILINESTRING" -> 2;
			default -> 3;
		};
	}

	// --- attributes -------------------------------------------------------------------

	private Map<String, LayerField> fieldsByColumn(UUID layerId) {
		Map<String, LayerField> result = new LinkedHashMap<>();
		for (LayerField field : fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId)) {
			result.put(field.getColumnName(), field);
		}
		return result;
	}

	/**
	 * Resolves the client's property keys to real columns.
	 *
	 * <p>Every key has to be a field of this layer -- the resolved column_name is what
	 * gets quoted, never the key itself. Same rule as {@link FilterParser}: identifiers
	 * come from the catalog, values are bound.
	 */
	private void collectProperties(Map<String, LayerField> fields, Map<String, Object> properties,
			List<String> columns, List<Object> values) {
		if (properties == null) {
			return;
		}
		for (Map.Entry<String, Object> property : properties.entrySet()) {
			LayerField field = fields.get(property.getKey());
			if (field == null) {
				throw new BadRequestException("Unbekanntes Feld: " + property.getKey()
						+ ". Verfügbar: " + String.join(", ", fields.keySet()) + ".");
			}
			columns.add(SqlIdentifier.quoteColumn(field.getColumnName()));
			values.add(toColumnValue(field, property.getValue()));
		}
	}

	/**
	 * Converts one incoming property value to the Java type its column needs before it is
	 * bound as a JDBC parameter.
	 *
	 * <p>Jackson decodes the request body generically -- every JSON value becomes
	 * whatever plain Java type the token implies (String, Boolean, Integer/Long/Double),
	 * with no awareness of the column it is destined for. That already matches what JDBC
	 * needs for the numeric types and boolean. It does not for {@code date}, {@code time},
	 * {@code timestamptz}, {@code uuid} and {@code bytea}: {@link FeatureQueryService}
	 * reads all five back as JSON strings (dates, times and timestamps as ISO-8601 text,
	 * bytea as base64), and PostgreSQL has no implicit cast from varchar to any of them --
	 * binding the raw string sends the parameter as varchar and the statement is
	 * rejected outright.
	 *
	 * <p>Parsing every column here, not only those five, is also what turns a value that
	 * plain does not fit the column -- a string where {@code layer_field.data_type} says
	 * a number, an unparsable date -- into a {@link BadRequestException} naming the
	 * field, instead of a bare 500 once PostgreSQL rejects the statement.
	 */
	private static Object toColumnValue(LayerField field, Object value) {
		if (value == null) {
			return null;
		}
		String type = field.getDataType().toLowerCase(Locale.ROOT);
		return switch (type) {
			case "integer", "smallint" -> asNumber(field, value).intValue();
			case "bigint" -> asNumber(field, value).longValue();
			case "double precision", "real" -> asNumber(field, value).doubleValue();
			case "numeric", "decimal" -> asBigDecimal(field, value);
			case "boolean" -> asBoolean(field, value);
			case "date" -> asLocalDate(field, value);
			case "time" -> asLocalTime(field, value);
			case "uuid" -> asUuid(field, value);
			case "bytea" -> asBytes(field, value);
			// timestamptz is the only timestamp-like type this application ever creates
			// (TypeMapper never emits a bare "timestamp"), but the prefix check covers
			// that variant too rather than silently mis-binding it as text.
			default -> type.startsWith("timestamp") ? asOffsetDateTime(field, value) : asText(field, value);
		};
	}

	private static Number asNumber(LayerField field, Object value) {
		if (value instanceof Number number) {
			return number;
		}
		throw typeMismatch(field, value);
	}

	private static BigDecimal asBigDecimal(LayerField field, Object value) {
		Number number = asNumber(field, value);
		return number instanceof BigDecimal decimal ? decimal : BigDecimal.valueOf(number.doubleValue());
	}

	private static Boolean asBoolean(LayerField field, Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		throw typeMismatch(field, value);
	}

	private static String asText(LayerField field, Object value) {
		if (value instanceof String text) {
			return text;
		}
		throw typeMismatch(field, value);
	}

	private static LocalDate asLocalDate(LayerField field, Object value) {
		try {
			return LocalDate.parse(asText(field, value));
		}
		catch (DateTimeParseException ex) {
			throw typeMismatch(field, value);
		}
	}

	/**
	 * Unlike {@code date}, a {@code time} column already reads back correctly as a plain
	 * {@code "HH:mm:ss"} string -- {@link FeatureQueryService} needs no conversion for it,
	 * because Jackson's own handling of {@link java.sql.Time} formats the time of day
	 * directly and does not go through the timezone-dependent {@code java.util.Date}
	 * instant logic that made {@code date} read back wrong. Only the write side has the
	 * same problem as the other four: the incoming string is Jackson's generic type, and
	 * PostgreSQL has no implicit cast from varchar to {@code time}.
	 */
	private static LocalTime asLocalTime(LayerField field, Object value) {
		try {
			return LocalTime.parse(asText(field, value));
		}
		catch (DateTimeParseException ex) {
			throw typeMismatch(field, value);
		}
	}

	/**
	 * {@code timestamptz} reads back as a UTC instant (see {@link FeatureQueryService}),
	 * which {@link OffsetDateTime#parse} accepts just as well as an explicit offset --
	 * ISO_OFFSET_DATE_TIME treats {@code Z} as zero offset. {@link java.time.Instant}
	 * looks like the more natural fit but pgjdbc's {@code setObject} cannot derive a SQL
	 * type for it (JDBC 4.2 maps {@code TIMESTAMP WITH TIME ZONE} to
	 * {@code OffsetDateTime}, not {@code Instant}), so binding it throws a
	 * {@code PSQLException} before the statement ever runs.
	 */
	private static OffsetDateTime asOffsetDateTime(LayerField field, Object value) {
		try {
			return OffsetDateTime.parse(asText(field, value));
		}
		catch (DateTimeParseException ex) {
			throw typeMismatch(field, value);
		}
	}

	private static UUID asUuid(LayerField field, Object value) {
		try {
			return UUID.fromString(asText(field, value));
		}
		catch (IllegalArgumentException ex) {
			throw typeMismatch(field, value);
		}
	}

	private static byte[] asBytes(LayerField field, Object value) {
		try {
			return Base64.getDecoder().decode(asText(field, value));
		}
		catch (IllegalArgumentException ex) {
			throw typeMismatch(field, value);
		}
	}

	/**
	 * Named by {@code sourceName}, not {@code columnName}: this is the identifier the
	 * client showed the user in the first place (see {@link FeatureDtos.Feature}), so it
	 * is the one that lets them find the offending field again in the UI.
	 */
	private static BadRequestException typeMismatch(LayerField field, Object value) {
		return new BadRequestException("Feld " + field.getSourceName() + " erwartet den Typ "
				+ field.getDataType() + ". Erhalten: " + describe(value) + ".");
	}

	/**
	 * The received value with its JSON type, not just its printed form.
	 *
	 * Without the type the message contradicts itself for the one mistake clients
	 * actually make: sending a number as a string produces "erwartet den Typ integer.
	 * Erhalten: 6", and 6 is a perfectly good integer to anyone reading it. Naming the
	 * type is what turns that into something a client author can act on.
	 */
	private static String describe(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof String text) {
			return "\"" + text + "\" (Text)";
		}
		if (value instanceof Boolean) {
			return value + " (Ja/Nein)";
		}
		return String.valueOf(value);
	}

	// --- bookkeeping ------------------------------------------------------------------

	/** @see LayerBookkeeping */
	private EditDtos.Response finish(Layer layer, String table, Map<Long, Long> createdFids,
			int updated, int deleted) {
		long featureCount = bookkeeping.recount(layer, table);

		return new EditDtos.Response(createdFids, updated, deleted,
				layer.getDataVersion(), featureCount);
	}

	private static String rootMessage(Throwable throwable) {
		Throwable root = throwable;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root.getMessage();
	}
}
