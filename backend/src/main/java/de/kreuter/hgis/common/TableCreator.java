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
	 * Creates the {@code gis_data} table, its GiST index on {@code geom}, and the
	 * {@code layer} plus {@code layer_field} catalog rows. The table always uses the
	 * project's storage SRID -- payload geometry never lives in any other CRS.
	 */
	public CreatedLayer createLayerTable(Project project, SourceSchema schema, String layerName) {
		UUID layerId = Uuid7.generate();
		String tableName = SqlIdentifier.tableName(layerId);

		List<ColumnMapping> columns = mapColumns(schema.fields());

		createTable(tableName, schema.geometryType(), project.getSrid(), columns);
		createGeometryIndex(tableName);

		Layer layer = new Layer(layerId, project, layerName, tableName,
				schema.geometryType().name(), project.getSrid());
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

	private List<ColumnMapping> mapColumns(List<SourceField> fields) {
		LinkedHashSet<String> taken = new LinkedHashSet<>();
		List<ColumnMapping> columns = new ArrayList<>(fields.size());
		for (SourceField field : fields) {
			String columnName = SqlIdentifier.toColumnName(field.name(), taken);
			taken.add(columnName);
			columns.add(new ColumnMapping(field.name(), columnName, TypeMapper.toPostgresType(field.javaType())));
		}
		return columns;
	}

	private void createTable(String tableName, SourceSchema.GeometryType geometryType, int srid,
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

	private static String postgisGeometryType(SourceSchema.GeometryType type) {
		return switch (type) {
			case MULTIPOINT -> "MultiPoint";
			case MULTILINESTRING -> "MultiLineString";
			case MULTIPOLYGON -> "MultiPolygon";
			case GEOMETRY -> "Geometry";
		};
	}
}
