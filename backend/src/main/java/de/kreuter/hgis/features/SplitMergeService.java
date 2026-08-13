package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.ConflictException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.dto.SplitMergeDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Splits one saved feature along a line and merges several into one
 * (CONTRACT.md section 12).
 *
 * <p>Both write immediately rather than joining the edit batch of section 10, and both do
 * it in a single transaction. The reason is the same for each: PostGIS computes the
 * geometry, so the result does not exist until the server has produced it, and a client's
 * local buffer would be stale the moment it did.
 *
 * <p><strong>The geometry is computed by PostGIS, never in Java.</strong> {@code ST_Split}
 * and {@code ST_Union} produce the parts; Java only carries the finished geometry back as
 * opaque EWKB, decides which part the original keeps, and copies the attributes across.
 *
 * <p>Neither operation is undoable. That is the price of writing straight through, and it
 * is the same deal a delete already makes -- which is why both take a row lock and check
 * every {@code rowVersion} before they write anything at all.
 */
@Service
public class SplitMergeService {

	/** Fewer than two features are not a merge (CONTRACT.md 12.2). */
	private static final int MIN_MERGE_PARTS = 2;

	/** Ceiling the contract puts on one merge (CONTRACT.md 12.2). */
	private static final int MAX_MERGE_PARTS = 100;

	/**
	 * The one dimension neither operation accepts. Compared against
	 * {@link #kindOf(String)}, so it covers the single and the multi form alike.
	 */
	private static final String KIND_POINT = "PUNKT";

	/** The blade of a split: what {@code ST_Split} accepts for a line or an area. */
	private static final Set<String> BLADE_TYPES = Set.of("LINESTRING", "MULTILINESTRING");

	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final JdbcClient jdbc;
	private final LayerBookkeeping bookkeeping;

	SplitMergeService(LayerRepository layerRepository, LayerFieldRepository fieldRepository,
			JdbcClient jdbc, LayerBookkeeping bookkeeping) {
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.jdbc = jdbc;
		this.bookkeeping = bookkeeping;
	}

	/** One saved row, as much of it as either operation has to decide on before writing. */
	private record RowState(long fid, String rowVersion, String geometryType, long leafCount) {
	}

	/** One geometry PostGIS produced: the finished EWKB plus what validity it has. */
	private record Computed(byte[] wkb, boolean valid, String reason) {
	}

	// --- split ------------------------------------------------------------------------

	/**
	 * Cuts one feature along {@code line} (CONTRACT.md 12.1).
	 *
	 * <p>The original keeps its fid and takes the first part, so anything holding that fid
	 * -- a selection, an open attribute form -- stays valid. Every further part becomes a
	 * new row carrying the original's attributes unchanged: splitting a shape does not
	 * decide what its halves mean.
	 */
	@Transactional
	public SplitMergeDtos.SplitResponse split(UUID layerId, long fid,
			SplitMergeDtos.SplitRequest request) {
		Layer layer = require(layerId);
		String table = SqlIdentifier.quoteLayerTable(layer.getTableName());

		requirePointFreeLayer(layer, "Punkte lassen sich nicht teilen.");
		requireBlade(request.line());

		RowState row = lockRow(table, fid);
		requirePointFreeRow(row, "Punkte lassen sich nicht teilen.");
		requireRowVersion(fid, request.rowVersion(), row.rowVersion());

		List<Computed> parts = splitParts(table, layer.getSrid(), fid, request.line(), row.leafCount());
		parts.forEach(SplitMergeService::requireValid);

		// The original first, still under the lock taken above, so no concurrent write can
		// slip between the check and this.
		jdbc.sql("UPDATE " + table + " SET geom = ST_GeomFromEWKB(:g) WHERE fid = :fid")
				.param("g", parts.get(0).wkb())
				.param("fid", fid)
				.update();

		List<String> columns = attributeColumns(layerId);
		List<Long> fids = new ArrayList<>();
		fids.add(fid);
		for (Computed part : parts.subList(1, parts.size())) {
			fids.add(copyRow(table, columns, fid, part.wkb()));
		}

		long featureCount = bookkeeping.recount(layer, table);
		return new SplitMergeDtos.SplitResponse(fids, layer.getDataVersion(), featureCount);
	}

	/**
	 * Every part the cut produces, largest first.
	 *
	 * <p>{@code ST_Split} always answers with a collection, whether it cut anything or not:
	 * a blade that misses the feature entirely returns the feature back inside one. The
	 * call therefore proves nothing by succeeding, and the only usable signal is that the
	 * feature now consists of <em>more pieces than it did before</em> -- which is why
	 * {@code leafCount} is measured on the stored geometry and compared here. Counting the
	 * parts alone would be wrong for every multi-part feature: a MULTIPOLYGON of two
	 * separate polygons already comes back as two parts when the blade touches neither.
	 *
	 * <p>The order is the contract's open question. {@code ST_Split} promises none, and an
	 * arbitrary one would leave the user unable to predict which piece keeps the fid. The
	 * order chosen here is <strong>the largest part first</strong> -- by area for a face,
	 * by length for a line, the other measure being 0 for each -- so the original stays the
	 * piece a user would still recognise as it. Equal halves fall back to position, west
	 * before east and south before north, which decides a symmetric cut without a coin toss.
	 *
	 * @param leafCount how many single geometries the feature consisted of before the cut
	 */
	private List<Computed> splitParts(String table, int srid, long fid, JsonNode line, long leafCount) {
		String sql = """
				WITH blade AS (
				  SELECT ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(:line), 4326), %d) AS g
				),
				src AS (
				  SELECT f.geom FROM %s f WHERE f.fid = :fid
				),
				part AS (
				  SELECT d.geom AS g FROM src, blade b, LATERAL ST_Dump(ST_Split(src.geom, b.g)) d
				)
				SELECT ST_AsEWKB(ST_Multi(p.g)) AS wkb,
				       ST_IsValid(p.g) AS valid,
				       ST_IsValidReason(p.g) AS reason
				FROM part p
				ORDER BY ST_Area(p.g) DESC, ST_Length(p.g) DESC, ST_XMin(p.g), ST_YMin(p.g)
				""".formatted(srid, table);

		List<Map<String, Object>> rows;
		try {
			rows = jdbc.sql(sql).param("line", line.toString()).param("fid", fid).query().listOfRows();
		}
		catch (RuntimeException ex) {
			// GEOS refuses to cut geometry it considers broken. That is a property of the
			// stored shape, not a server fault, so it is reported as one.
			throw new BadRequestException("Das Programm kann das Objekt nicht teilen: " + rootMessage(ex));
		}

		if (rows.size() <= leafCount) {
			throw new BadRequestException("Die Linie teilt das Objekt nicht.");
		}
		return rows.stream().map(SplitMergeService::toComputed).toList();
	}

	/**
	 * Copies one row, geometry replaced. The attributes come straight out of the source row
	 * in SQL rather than through Java, so no value is ever converted on the way -- "parts
	 * inherit the original's attributes, unchanged" holds for every column type, base64
	 * blobs and timestamps included.
	 *
	 * @return the fid the identity column assigned
	 */
	private long copyRow(String table, List<String> columns, long sourceFid, byte[] wkb) {
		StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (geom");
		columns.forEach(column -> sql.append(", ").append(column));
		sql.append(") SELECT ST_GeomFromEWKB(:g)");
		columns.forEach(column -> sql.append(", f.").append(column));
		sql.append(" FROM ").append(table).append(" f WHERE f.fid = :fid RETURNING fid");

		return jdbc.sql(sql.toString())
				.param("g", wkb)
				.param("fid", sourceFid)
				.query(Long.class)
				.single();
	}

	// --- merge ------------------------------------------------------------------------

	/**
	 * Joins several features into the lead (CONTRACT.md 12.2).
	 *
	 * <p>The lead keeps its fid and every attribute value, the others are deleted, and the
	 * geometry is the union of all of them forced to the layer's multi form. Parts need not
	 * touch: two separate polygons legitimately become one MULTIPOLYGON.
	 */
	@Transactional
	public SplitMergeDtos.MergeResponse merge(UUID layerId, SplitMergeDtos.MergeRequest request) {
		Layer layer = require(layerId);
		String table = SqlIdentifier.quoteLayerTable(layer.getTableName());

		requirePointFreeLayer(layer, "Punkte lassen sich nicht zusammenführen.");

		// The ceiling is read off the request as it arrived, before duplicates are dropped:
		// it guards the size of the statement below, and a list of ten thousand repetitions
		// costs the same to send as one of ten thousand features.
		int sent = request.fids() == null ? 0 : request.fids().size();
		if (sent > MAX_MERGE_PARTS) {
			throw new BadRequestException("Zusammenführen umfasst " + sent
					+ " Objekte. Erlaubt sind " + MAX_MERGE_PARTS + ".");
		}
		List<Long> fids = distinct(request.fids());
		if (fids.size() < MIN_MERGE_PARTS) {
			throw new BadRequestException("Zusammenführen braucht mindestens zwei verschiedene Objekte.");
		}
		long leadFid = leadOf(request, fids);

		List<RowState> rows = lockRows(table, fids);
		requireSameKind(rows);

		// Every version is checked before the first write, and the rows are locked while it
		// happens: a batch that would conflict must leave nothing behind (CONTRACT.md 12.2).
		for (RowState row : rows) {
			requireRowVersion(row.fid(), request.rowVersions().get(String.valueOf(row.fid())),
					row.rowVersion());
		}

		Computed union = union(table, fids);
		requireValid(union);

		jdbc.sql("UPDATE " + table + " SET geom = ST_GeomFromEWKB(:g) WHERE fid = :fid")
				.param("g", union.wkb())
				.param("fid", leadFid)
				.update();

		Long[] others = fids.stream().filter(fid -> fid != leadFid).toArray(Long[]::new);
		jdbc.sql("DELETE FROM " + table + " WHERE fid = ANY(:fids)")
				.param("fids", others)
				.update();

		long featureCount = bookkeeping.recount(layer, table);
		return new SplitMergeDtos.MergeResponse(leadFid, layer.getDataVersion(), featureCount);
	}

	/**
	 * The lead's fid, which the client names explicitly: the user picked it, and order in a
	 * list is not a decision.
	 */
	private static long leadOf(SplitMergeDtos.MergeRequest request, List<Long> fids) {
		Long leadFid = request.leadFid();
		if (leadFid == null || !fids.contains(leadFid)) {
			throw new BadRequestException("Das führende Objekt gehört nicht zur Auswahl.");
		}
		return leadFid;
	}

	/** The union of every part, in the layer's multi form. */
	private Computed union(String table, List<Long> fids) {
		String sql = """
				WITH u AS (
				  SELECT ST_Union(f.geom) AS g FROM %s f WHERE f.fid = ANY(:fids)
				)
				SELECT ST_AsEWKB(ST_Multi(u.g)) AS wkb,
				       ST_IsValid(u.g) AS valid,
				       ST_IsValidReason(u.g) AS reason
				FROM u
				""".formatted(table);
		try {
			return toComputed(jdbc.sql(sql).param("fids", fids.toArray(Long[]::new)).query().singleRow());
		}
		catch (BadRequestException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// Same reasoning as in splitParts: GEOS refusing the input is about the data.
			throw new BadRequestException(
					"Das Programm kann die Objekte nicht zusammenführen: " + rootMessage(ex));
		}
	}

	// --- shared -----------------------------------------------------------------------

	private Layer require(UUID layerId) {
		return layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));
	}

	/**
	 * Reads one row and holds it for the rest of the transaction.
	 *
	 * <p>{@code FOR UPDATE} rather than the optimistic guard {@link EditService} puts on its
	 * own {@code UPDATE}: both operations read the geometry, compute from it and write it
	 * back in separate statements, and only a lock keeps those three looking at the same
	 * row. The lock also makes the version that comes back the freshest committed one, so a
	 * client that lost a race learns it here rather than after the write.
	 */
	private RowState lockRow(String table, long fid) {
		List<RowState> rows = lockRows(table, List.of(fid));
		return rows.get(0);
	}

	/**
	 * The same for several rows, and the point at which a merge becomes safe: with every
	 * row locked, no version can change between the check and the write, so "nothing is
	 * written" holds for the whole batch rather than only for the row that conflicted.
	 *
	 * <p>Locked in fid order on purpose. Two merges over overlapping selections that took
	 * their locks in scan order could each hold what the other waits for.
	 */
	private List<RowState> lockRows(String table, List<Long> fids) {
		List<RowState> rows = jdbc.sql("""
				SELECT f.fid,
				       f.xmin::text AS row_version,
				       GeometryType(f.geom) AS geometry_type,
				       (SELECT count(*) FROM ST_Dump(f.geom)) AS leaf_count
				FROM %s f
				WHERE f.fid = ANY(:fids)
				ORDER BY f.fid
				FOR UPDATE
				""".formatted(table))
				.param("fids", fids.toArray(Long[]::new))
				.query((rs, index) -> new RowState(rs.getLong("fid"), rs.getString("row_version"),
						rs.getString("geometry_type"), rs.getLong("leaf_count")))
				.list();

		if (rows.size() != fids.size()) {
			Set<Long> found = rows.stream().map(RowState::fid).collect(Collectors.toSet());
			long missing = fids.stream().filter(fid -> !found.contains(fid)).findFirst().orElseThrow();
			throw new NotFoundException("Objekt " + missing + " existiert nicht mehr");
		}
		return rows;
	}

	/**
	 * A layer of points refuses both operations outright (CONTRACT.md section 12). On a
	 * {@code GEOMETRY} layer this says nothing -- the column type carries no dimension
	 * there, so the answer comes from {@link #requirePointFreeRow} instead, per feature and
	 * on the actual geometry.
	 */
	private static void requirePointFreeLayer(Layer layer, String message) {
		if ("MULTIPOINT".equals(layer.getGeometryType())) {
			throw new BadRequestException(message);
		}
	}

	private static void requirePointFreeRow(RowState row, String message) {
		if (KIND_POINT.equals(kindOf(row.geometryType()))) {
			throw new BadRequestException(message);
		}
	}

	/**
	 * Every part has to be of one dimension. A line and a face have no union that is either
	 * of them, and the layer's column could not hold the collection that would come out.
	 *
	 * <p>Points are named separately, before the mixture is: told only that the kinds
	 * differ, a user selecting a point and a face would go looking for the odd one out
	 * rather than learning that points are out of scope entirely.
	 */
	private static void requireSameKind(List<RowState> rows) {
		for (RowState row : rows) {
			requirePointFreeRow(row, "Punkte lassen sich nicht zusammenführen.");
		}
		String first = kindOf(rows.get(0).geometryType());
		for (RowState row : rows) {
			if (!first.equals(kindOf(row.geometryType()))) {
				throw new BadRequestException(
						"Nur Objekte derselben Geometrieart lassen sich zusammenführen.");
			}
		}
	}

	/**
	 * The dimension behind a PostGIS geometry type. The single and the multi form are the
	 * same kind: everything this application stores is promoted with {@code ST_Multi}, so a
	 * POLYGON and a MULTIPOLYGON are the same thing seen at different moments.
	 */
	private static String kindOf(String geometryType) {
		return switch (geometryType == null ? "" : geometryType.toUpperCase(Locale.ROOT)) {
			case "POINT", "MULTIPOINT" -> KIND_POINT;
			case "LINESTRING", "MULTILINESTRING", "LINEARRING" -> "LINIE";
			case "POLYGON", "MULTIPOLYGON" -> "FLAECHE";
			default -> geometryType == null ? "" : geometryType.toUpperCase(Locale.ROOT);
		};
	}

	/**
	 * The blade has to be a line before it reaches {@code ST_Split}. PostGIS raises a bare
	 * SQL error for a blade it cannot cut with -- a point against a face, say -- and that
	 * would surface as a 500 naming nothing the user could act on.
	 */
	private void requireBlade(JsonNode line) {
		String type;
		try {
			type = jdbc.sql("SELECT GeometryType(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326))")
					.param("g", line.toString())
					.query(String.class)
					.single();
		}
		catch (RuntimeException ex) {
			// Malformed GeoJSON never reaches GeometryType; PostGIS rejects it while
			// parsing, and the raw message is more useful than anything generic.
			throw new BadRequestException("Das Programm kann die Teilungslinie nicht lesen: "
					+ rootMessage(ex));
		}
		if (type == null || !BLADE_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
			throw new BadRequestException("Die Teilungslinie muss eine Linie sein.");
		}
	}

	/**
	 * Same rule as the edit batch: the row version is the {@code xmin} the feature came
	 * with, and omitting it skips the check (CONTRACT.md section 10).
	 */
	private static void requireRowVersion(long fid, String expected, String actual) {
		if (expected == null || expected.equals(actual)) {
			return;
		}
		Map<String, Object> current = new LinkedHashMap<>();
		current.put("fid", fid);
		current.put("row_version", actual);
		throw new ConflictException(
				"Eine andere Stelle hat Objekt " + fid + " zwischenzeitlich geändert", current);
	}

	/**
	 * Never repaired silently -- section 10's rule holds here too. A split or a merge that
	 * would store a broken shape is refused instead, because neither operation can be
	 * undone and there is no second chance to notice.
	 */
	private static void requireValid(Computed geometry) {
		if (geometry.valid()) {
			return;
		}
		throw new BadRequestException("Ungültige Geometrie: " + geometry.reason()
				+ ". Das Ergebnis wird nicht gespeichert.");
	}

	private static Computed toComputed(Map<String, Object> row) {
		byte[] wkb = (byte[]) row.get("wkb");
		if (wkb == null) {
			// ST_Union over rows that are all gone, or a geometry that collapsed to nothing.
			throw new BadRequestException("Das Ergebnis hat keine Geometrie.");
		}
		return new Computed(wkb, Boolean.TRUE.equals(row.get("valid")), (String) row.get("reason"));
	}

	/** The layer's columns, in catalog order -- the same resolution the edit batch uses. */
	private List<String> attributeColumns(UUID layerId) {
		return fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId).stream()
				.map(LayerField::getColumnName)
				.map(SqlIdentifier::quoteColumn)
				.toList();
	}

	/**
	 * The fids without repetitions, in the order the client sent them. A selection that
	 * names the same feature twice is one feature, not two -- and counting it twice would
	 * let a two-entry request through the "at least two" check with nothing to merge.
	 */
	private static List<Long> distinct(List<Long> fids) {
		if (fids == null) {
			return List.of();
		}
		return List.copyOf(fids.stream().filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new)));
	}

	private static String rootMessage(Throwable throwable) {
		Throwable root = throwable;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root.getMessage();
	}
}
