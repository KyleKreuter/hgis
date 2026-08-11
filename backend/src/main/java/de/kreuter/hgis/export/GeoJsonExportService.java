package de.kreuter.hgis.export;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes a layer, or a selection within it, as an RFC 7946 {@code FeatureCollection}.
 *
 * <p>The features are assembled by PostGIS, not by Java: one row of the query is the
 * finished text of a GeoJSON feature, and this class only moves that text from a
 * server-side cursor into the response. Nothing accumulates -- a layer of a million
 * features costs the same heap as one of ten. The obvious alternative, reading rows into
 * DTOs and letting Jackson serialise the collection, holds the entire export in memory
 * twice over, and an export is precisely the request where "the whole layer" is the
 * normal case.
 *
 * <p>Geometry is transformed to EPSG:4326 on the way out because RFC 7946 admits no other
 * coordinate reference system; the projected storage CRS of the project stays where it
 * is. Consequently the file carries no {@code crs} member -- the 2008 specification had
 * one, RFC 7946 removed it, and writing it anyway is how a file ends up being read in two
 * different ways.
 */
@Service
public class GeoJsonExportService {

	private static final Logger log = LoggerFactory.getLogger(GeoJsonExportService.class);

	/** RFC 7946, section 4: WGS 84 longitude/latitude, and nothing else. */
	private static final int GEOJSON_SRID = 4326;

	/**
	 * Rows the driver pulls per round trip. Large enough that the cursor is not the
	 * bottleneck, small enough that the buffered slice stays negligible.
	 */
	private static final int FETCH_SIZE = 500;

	private static final int WRITE_BUFFER_BYTES = 32 * 1024;

	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	GeoJsonExportService(LayerRepository layerRepository, LayerFieldRepository fieldRepository,
			JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Everything {@link #write} needs, with no catalog entity left in it -- and with the
	 * statement already built, so that nothing about the query can fail once the status
	 * code is out.
	 *
	 * @param sql the finished feature query, one bind parameter per field and, for a
	 *            selection, one more for the array of row ids
	 */
	public record Export(UUID layerId, String layerName, String sql, List<ExportField> fields,
			FidSelection selection) {
	}

	/**
	 * Resolves a request against the catalog and builds the statement for it.
	 *
	 * <p>Separate from {@link #write} on purpose: an unknown layer, or a catalog row whose
	 * identifiers no longer pass {@link SqlIdentifier}, has to fail while the response is
	 * still a status code. Once the body has started there is no way back -- the client
	 * would receive a 200 with half a FeatureCollection and no indication that anything
	 * went wrong. Building the SQL here rather than on the streaming thread is what makes
	 * that guarantee hold for the query text as well, not only for the lookup.
	 *
	 * @throws NotFoundException if no such layer exists
	 * @throws IllegalArgumentException if a catalog identifier is not safe to quote
	 */
	@Transactional(readOnly = true)
	public Export prepare(UUID layerId, FidSelection selection) {
		Layer layer = layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));

		List<ExportField> fields =
				PropertyNaming.resolve(fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId));
		String sql = featureQuery(layer.getTableName(), fields, !selection.isWholeLayer());

		return new Export(layerId, layer.getName(), sql, fields, selection);
	}

	/**
	 * Streams the collection. Runs on the container's async thread, well after the
	 * controller returned, so it opens its own transaction rather than inheriting one.
	 *
	 * <p>Nothing is thrown for a client that stopped reading. By the time the first byte
	 * is written the only party that could still be told about a problem is the one that
	 * has just left, and a download the user cancelled is not a fault of the server; it is
	 * logged with the layer and the number of features that made it out, and that is all.
	 * A failure on the database side is a different matter and is rethrown.
	 */
	@Transactional(readOnly = true)
	public void write(Export export, OutputStream out) {
		Writer writer = new BufferedWriter(
				new OutputStreamWriter(out, StandardCharsets.UTF_8), WRITE_BUFFER_BYTES);
		AtomicLong written = new AtomicLong();

		try {
			// "name" is a foreign member, allowed by RFC 7946 section 6.1 and the
			// convention GDAL writes: without it QGIS names the layer after the file,
			// which is the sanitised ASCII form rather than the one the user gave it.
			writer.write("{\"type\":\"FeatureCollection\",\"name\":");
			writer.write(objectMapper.writeValueAsString(export.layerName()));
			writer.write(",\"features\":[");

			// An empty selection is an empty collection, and asking the database to prove
			// it would only be a scan for a result already known.
			if (!export.selection().isEmptySelection()) {
				writeFeatures(export, writer, written);
			}

			writer.write("]}");
			writer.flush();
		}
		catch (IOException ex) {
			// The response body is the only sink here, so an I/O failure means the
			// transport is gone -- a cancelled download, a closed tab, a proxy that gave
			// up. Turning that into a 500 would put a server error in the log for
			// something the server did correctly.
			log.info("Export von Layer {} nach {} Features abgebrochen: {}",
					export.layerId(), written.get(), ex.getMessage());
		}
		catch (RuntimeException ex) {
			// Anything else is ours. The count says whether the client already has a
			// truncated file, which is the part that cannot be seen from the stack trace.
			log.error("Export von Layer {} nach {} Features fehlgeschlagen",
					export.layerId(), written.get(), ex);
			throw ex;
		}
	}

	// --- streaming ---------------------------------------------------------------

	private void writeFeatures(Export export, Writer writer, AtomicLong written)
			throws IOException {
		RowCallbackHandler handler = resultSet -> {
			try {
				if (written.getAndIncrement() > 0) {
					writer.write(',');
				}
				writer.write(resultSet.getString(1));
			}
			catch (IOException ex) {
				// The handler may not throw it; a broken pipe halfway through a large
				// download is ordinary, and it has to reach the caller as itself.
				throw new UncheckedIOException(ex);
			}
		};

		try {
			jdbcTemplate.query(statement(export), handler);
		}
		catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
	}

	private PreparedStatementCreator statement(Export export) {
		// JdbcClient, used everywhere else in this code base, has no way to set a fetch
		// size or to bind an array in one parameter -- both of which are the point here.
		return connection -> {
			PreparedStatement statement = connection.prepareStatement(export.sql(),
					ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

			// Without this the driver reads the complete result before handing over the
			// first row, which is exactly the heap the streaming exists to avoid. It only
			// takes effect inside a transaction: with autocommit on, PostgreSQL's driver
			// has nowhere to keep a server-side cursor and silently ignores the hint.
			statement.setFetchSize(FETCH_SIZE);

			int index = 1;
			for (ExportField field : export.fields()) {
				statement.setString(index++, field.propertyKey());
			}
			if (!export.selection().isWholeLayer()) {
				// One array parameter rather than an expanded IN list: a selection of
				// thousands would otherwise run into PostgreSQL's 65535 parameter limit.
				statement.setArray(index, connection.createArrayOf("bigint",
						export.selection().fids().toArray(Long[]::new)));
			}
			return statement;
		};
	}

	// --- query -------------------------------------------------------------------

	/** One row, one finished GeoJSON feature. */
	private static String featureQuery(String tableName, List<ExportField> fields,
			boolean selected) {
		return "SELECT json_build_object("
				+ "'type', 'Feature', "
				+ "'id', f.fid, "
				+ "'geometry', ST_AsGeoJSON(" + orientedGeometry() + ")::json, "
				+ "'properties', p.props)::text"
				+ " FROM " + SqlIdentifier.quoteLayerTable(tableName) + " f"
				+ " CROSS JOIN LATERAL (SELECT ST_Transform(f.geom, " + GEOJSON_SRID
				+ ") AS geom) g"
				+ " CROSS JOIN LATERAL (" + properties(fields) + ") p"
				+ (selected ? " WHERE f.fid = ANY(?)" : "")
				// A stable order costs an index scan on the primary key and makes two
				// exports of unchanged data comparable, which is what makes them testable.
				+ " ORDER BY f.fid";
	}

	/**
	 * Polygon rings, and only those, are turned the way RFC 7946 section 3.1.6 wants
	 * them: exterior counter-clockwise, holes clockwise. PostGIS stores whatever the
	 * source had, and a reader that honours the rule sees an unturned exterior ring as a
	 * hole.
	 *
	 * <p>The type test is not redundant. {@code ST_ForcePolygonCCW} is defined as
	 * {@code ST_Reverse(ST_ForcePolygonCW(geom))}, so on every PostGIS up to and including
	 * 3.5.3 it also reverses the vertex order of lines -- an exported route or river then
	 * runs backwards, which no reader can detect and no rule licenses. Later versions
	 * added a guard of their own; this one does not depend on which version is installed.
	 *
	 * <p>A {@code GEOMETRYCOLLECTION} is deliberately left untouched. Applying the
	 * function to a collection reverses the lines inside it just the same, and PostGIS has
	 * no operation that reorients the polygonal members alone without rebuilding the
	 * collection. Between a winding order that RFC 7946 only recommends and a line whose
	 * direction is silently destroyed, the recommendation yields: mixed collections are
	 * exported exactly as stored.
	 */
	private static String orientedGeometry() {
		return "CASE WHEN ST_GeometryType(g.geom) IN ('ST_Polygon', 'ST_MultiPolygon')"
				+ " THEN ST_ForcePolygonCCW(g.geom) ELSE g.geom END";
	}

	/**
	 * The {@code properties} member of a feature, as a lateral aggregate over one row per
	 * attribute.
	 *
	 * <p>The obvious formulation is one {@code json_build_object} with a key and a column
	 * per attribute, and it works until a layer has fifty of them: PostgreSQL allows at
	 * most 100 arguments to a function, so 1 + 2 x 50 arguments is where an export stops
	 * with "cannot pass more than 100 arguments to a function". A table of pairs has no
	 * such ceiling -- a {@code VALUES} list is not a function call -- and 65535 bind
	 * parameters are the only remaining limit, far beyond any layer.
	 *
	 * <p>Types survive it. {@code to_jsonb} decides what a value looks like from its
	 * PostgreSQL type exactly as {@code json_build_object} does: a {@code bigint} stays a
	 * number, a {@code boolean} stays a boolean, a {@code date} becomes an ISO string, a
	 * {@code numeric} keeps its scale, and SQL NULL becomes JSON null rather than an empty
	 * string. {@code json_object_agg} -- not the jsonb variant, which would reorder the
	 * keys by its own rules -- keeps the attributes in the layer's order, which is what
	 * the {@code ORDER BY} on the ordinal is for.
	 *
	 * <p>Each key is a bound parameter. It comes from the source file and has no business
	 * in statement text, however harmless it looks. The column name is an identifier and
	 * cannot be bound, so it goes through {@link SqlIdentifier} -- and it was never client
	 * input to begin with, only a lookup in {@code layer_field}.
	 */
	private static String properties(List<ExportField> fields) {
		StringBuilder pairs = new StringBuilder("(VALUES (0, '")
				.append(PropertyNaming.FID_KEY).append("', to_jsonb(f.fid))");

		int ordinal = 0;
		for (ExportField field : fields) {
			pairs.append(", (").append(++ordinal).append(", CAST(? AS text), to_jsonb(f.")
					.append(SqlIdentifier.quoteColumn(field.columnName())).append("))");
		}
		pairs.append(") AS a(ord, k, v)");

		return "SELECT json_object_agg(a.k, a.v ORDER BY a.ord) AS props FROM " + pairs;
	}
}
