package de.kreuter.hgis.common;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Creates the physical table for a layer in {@code gis_data} together with its catalog
 * entries in {@code layer} and {@code layer_field}.
 *
 * Deliberately placed in {@code common} rather than {@code ingest}: geoprocessing will
 * later produce output layers of its own and needs exactly the same kind of table, so
 * this is shared infrastructure, not an import concern.
 *
 * Every method here runs inside whatever transaction the caller has open -- table
 * creation has to be atomic with the catalog rows and, during import, with the job
 * transitioning to RUNNING. That boundary is owned by the caller, not by this class.
 */
@Component
public class TableCreator {

	private final JdbcClient jdbc;
	private final LayerRepository layerRepository;
	private final LayerFieldRepository layerFieldRepository;

	public TableCreator(JdbcClient jdbc, LayerRepository layerRepository,
			LayerFieldRepository layerFieldRepository) {
		this.jdbc = jdbc;
		this.layerRepository = layerRepository;
		this.layerFieldRepository = layerFieldRepository;
	}

	/** One column of a newly created layer table: source name, safe column name, PG type. */
	public record ColumnMapping(String sourceName, String columnName, String pgType) {
	}

	/** Result of {@link #createLayerTable}: the persisted catalog row and its columns. */
	public record CreatedLayer(Layer layer, List<ColumnMapping> columns) {
	}

	/**
	 * One field to create alongside a brand-new, empty layer: its display name and the
	 * closed set of types a client may pick from. Unlike {@link SourceField}, which
	 * carries a Java class an import reader produced, this comes straight from a request
	 * and is never anything but one of {@link FieldType}'s nine values.
	 */
	public record NewField(String sourceName, FieldType type) {
	}

	/**
	 * Creates the {@code gis_data} table, its GiST index on {@code geom}, and the
	 * {@code layer} plus {@code layer_field} catalog rows. The table always uses the
	 * project's storage SRID -- payload geometry never lives in any other CRS.
	 */
	public CreatedLayer createLayerTable(Project project, SourceSchema schema, String layerName) {
		return buildLayer(project, schema.geometryType(), mapColumns(schema.fields(), null), layerName, -1);
	}

	/**
	 * Same as {@link #createLayerTable(Project, SourceSchema, String)}, for a reader whose
	 * {@link SourceField#name()} is not the name a column should be derived from, and/or
	 * that reports which of its fields carries the source's own stable identity.
	 *
	 * <p>The Geoportal import (CONTRACT.md phase 23) is the first caller that needs either.
	 * Its reader reports the German label as {@code SourceField#name()} so it lands in
	 * {@code layer_field.source_name} (decision E1), but E1 also promises the technical
	 * name stays filterable -- and {@code layer_field} has no third column to hold it
	 * separately, so that promise only holds if {@code column_name} is derived from the
	 * technical name instead of from the display name. {@code columnNameBasis} carries
	 * that name, one entry per entry of {@code schema.fields()}, in the same order.
	 *
	 * <p>{@code idFieldIndex} covers the other Geoportal-specific trap: the field carrying
	 * {@code x-ogc-role: id} is imported as an ordinary attribute (decision E6), and a
	 * later reconcile will look rows up by its value. The service does not promise that
	 * value is unique, so this adds a plain, non-unique index rather than a constraint --
	 * and does it here because table creation is the one moment an index costs nothing,
	 * the table being empty.
	 *
	 * @param columnNameBasis one entry per entry of {@code schema.fields()}; a null entry,
	 *                        or a null list, falls back to the field's own display name,
	 *                        matching every other caller
	 * @param idFieldIndex    index into {@code schema.fields()} of the field to add a
	 *                        non-unique index for, or -1 for none
	 */
	public CreatedLayer createLayerTable(Project project, SourceSchema schema, String layerName,
			List<String> columnNameBasis, int idFieldIndex) {
		return buildLayer(project, schema.geometryType(), mapColumns(schema.fields(), columnNameBasis), layerName,
				idFieldIndex);
	}

	/**
	 * Same table creation, for a layer a user creates empty and draws into by hand
	 * instead of importing. {@code GEOMETRY} is accepted here too, for a layer meant to
	 * hold a genuine mix of points, lines and polygons from the start -- the column ends
	 * up unconstrained by geometry subtype, exactly as it would for one produced by an
	 * import.
	 */
	public CreatedLayer createLayerTable(Project project, GeometryType geometryType, List<NewField> fields,
			String layerName) {
		return buildLayer(project, geometryType, mapNewColumns(fields), layerName, -1);
	}

	/** The part every public overload shares once their fields are reduced to plain columns. */
	private CreatedLayer buildLayer(Project project, GeometryType geometryType, List<ColumnMapping> columns,
			String layerName, int idFieldIndex) {
		UUID layerId = Uuid7.generate();
		String tableName = SqlIdentifier.tableName(layerId);

		createTable(tableName, geometryType, project.getSrid(), columns);
		createGeometryIndex(tableName);
		if (idFieldIndex >= 0) {
			createAttributeIndex(tableName, columns.get(idFieldIndex).columnName());
		}

		Layer layer = new Layer(layerId, project, layerName, tableName,
				geometryType.name(), project.getSrid());
		// Newly imported layers belong on top -- that is the one someone just asked to
		// see. Leaving them all at the default 0 would make the order ambiguous, and the
		// two consumers resolve a tie differently: the layer tree sorts descending and
		// shows the newest last, the map moves them in ascending order and draws it
		// first. Same data, opposite results.
		layer.setZIndex(layerRepository.maxZIndex(project.getId()) + 1);
		// Layer has no @GeneratedValue -- its id is always non-null by the time save() is
		// called, so Spring Data JPA's isNew() check treats it as existing and merges
		// rather than persists. merge() returns a different, managed instance; the
		// original reference stays transient, so it has to be replaced here before it is
		// used for the layer_field rows below.
		layer = layerRepository.save(layer);

		int ordinal = 0;
		for (ColumnMapping column : columns) {
			layerFieldRepository.save(
					new LayerField(layer, column.sourceName(), column.columnName(), column.pgType(), ordinal++));
		}

		return new CreatedLayer(layer, columns);
	}

	/**
	 * Widens an existing layer's payload table by one column -- the counterpart to
	 * {@link #createLayerTable} for a layer someone wants to extend instead of build
	 * fresh. Existing rows read back {@code NULL} for it, which needs no special
	 * handling: it is simply what {@code ALTER TABLE ... ADD COLUMN} leaves behind.
	 *
	 * @param columnName already through {@link SqlIdentifier#toColumnName}, unique
	 *                    against this layer's other columns
	 * @param pgType      always {@link FieldType#pgType()}, never a raw request value
	 */
	public void addColumn(String tableName, String columnName, String pgType) {
		jdbc.sql("ALTER TABLE " + SqlIdentifier.quoteLayerTable(tableName)
				+ " ADD COLUMN " + SqlIdentifier.quoteColumn(columnName) + " " + pgType)
				.update();
	}

	/**
	 * Narrows an existing layer's payload table by one column -- the counterpart to
	 * {@link #addColumn}, for a field a user wants to remove instead of add (CONTRACT.md
	 * phase 12). Whatever values the column held are gone with it; the caller decides
	 * whether that is acceptable and cleans up any catalog row and style reference that
	 * pointed at it, this only ever executes the DDL.
	 *
	 * @param columnName already through {@link SqlIdentifier#toColumnName} when the
	 *                    column was created, so it is safe to quote here unchanged
	 */
	public void dropColumn(String tableName, String columnName) {
		jdbc.sql("ALTER TABLE " + SqlIdentifier.quoteLayerTable(tableName)
				+ " DROP COLUMN " + SqlIdentifier.quoteColumn(columnName))
				.update();
	}

	/**
	 * Compensation for a failed or aborted import: drops the physical table and removes
	 * the catalog row. {@code layer_field} rows disappear with it via {@code ON DELETE
	 * CASCADE}. Used both by the import compensation path and by {@code JobJanitor} when
	 * cleaning up after a crash.
	 */
	public void dropLayer(UUID layerId, String tableName) {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.deleteById(layerId);
	}

	// --- internals ---------------------------------------------------------------------

	/**
	 * @param columnNameBasis see {@link #createLayerTable(Project, SourceSchema, String,
	 *                        List, int)}; null, or a null entry, falls back to the field's
	 *                        own display name
	 */
	private List<ColumnMapping> mapColumns(List<SourceField> fields, List<String> columnNameBasis) {
		LinkedHashSet<String> taken = new LinkedHashSet<>();
		List<ColumnMapping> columns = new ArrayList<>(fields.size());
		for (int i = 0; i < fields.size(); i++) {
			SourceField field = fields.get(i);
			String basis = columnNameBasis == null ? null : columnNameBasis.get(i);
			String columnName = SqlIdentifier.toColumnName(basis != null ? basis : field.name(), taken);
			taken.add(columnName);
			columns.add(new ColumnMapping(field.name(), columnName, TypeMapper.toPostgresType(field.javaType())));
		}
		return columns;
	}

	private List<ColumnMapping> mapNewColumns(List<NewField> fields) {
		LinkedHashSet<String> taken = new LinkedHashSet<>();
		List<ColumnMapping> columns = new ArrayList<>(fields.size());
		for (NewField field : fields) {
			String columnName = SqlIdentifier.toColumnName(field.sourceName(), taken);
			taken.add(columnName);
			columns.add(new ColumnMapping(field.sourceName(), columnName, field.type().pgType()));
		}
		return columns;
	}

	private void createTable(String tableName, GeometryType geometryType, int srid,
			List<ColumnMapping> columns) {
		StringBuilder ddl = new StringBuilder()
				.append("CREATE TABLE ").append(SqlIdentifier.quoteLayerTable(tableName)).append(" (\n")
				.append("    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,\n")
				.append("    geom geometry(").append(postgisGeometryType(geometryType)).append(", ").append(srid)
				.append(") NOT NULL");
		for (ColumnMapping column : columns) {
			ddl.append(",\n    ").append(SqlIdentifier.quoteColumn(column.columnName()))
					.append(' ').append(column.pgType());
		}
		ddl.append("\n)");
		jdbc.sql(ddl.toString()).update();
	}

	private void createGeometryIndex(String tableName) {
		// tableName is always 'layer_' + 32 hex digits (guaranteed by SqlIdentifier.tableName),
		// so appending a fixed, already-safe suffix needs no further normalisation -- only the
		// defensive re-validation that quoteColumn performs regardless of the source.
		String indexName = tableName + "_geom_idx";
		jdbc.sql("CREATE INDEX " + SqlIdentifier.quoteColumn(indexName) + " ON "
				+ SqlIdentifier.quoteLayerTable(tableName) + " USING GIST (geom)").update();
	}

	/**
	 * Non-unique index on one ordinary attribute column, for a source that carries its own
	 * stable feature id as data rather than as this table's primary key (CONTRACT.md phase
	 * 23, decision E6). Deliberately not unique: nothing here can promise the source never
	 * repeats a value, and a violated uniqueness constraint would fail the very import this
	 * is meant to make cheaper later.
	 */
	private void createAttributeIndex(String tableName, String columnName) {
		// tableName is always 38 characters (SqlIdentifier.tableName's fixed format) and
		// the suffix below is fixed too, so only the column name itself can push the
		// identifier past PostgreSQL's 63-character limit -- truncate defensively rather
		// than let quoteColumn reject a long field's index outright.
		String suffix = "_idx";
		int budget = SqlIdentifier.MAX_LENGTH - tableName.length() - 1 - suffix.length();
		String columnPart = columnName.length() > budget ? columnName.substring(0, budget) : columnName;
		String indexName = tableName + "_" + columnPart + suffix;
		jdbc.sql("CREATE INDEX " + SqlIdentifier.quoteColumn(indexName) + " ON "
				+ SqlIdentifier.quoteLayerTable(tableName) + " (" + SqlIdentifier.quoteColumn(columnName) + ")")
				.update();
	}

	private static String postgisGeometryType(GeometryType type) {
		return switch (type) {
			case MULTIPOINT -> "MultiPoint";
			case MULTILINESTRING -> "MultiLineString";
			case MULTIPOLYGON -> "MultiPolygon";
			case GEOMETRY -> "Geometry";
		};
	}
}
