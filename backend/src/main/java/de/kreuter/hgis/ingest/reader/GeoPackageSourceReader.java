package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import org.geotools.api.data.SimpleFeatureReader;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.Filter;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.locationtech.jts.geom.Geometry;

/**
 * Reads one vector table of a GeoPackage. A package can hold several; since one import
 * produces one layer, the first vector table wins -- there is no place in the import
 * contract to ask the user which one they meant.
 */
final class GeoPackageSourceReader extends AbstractSourceReader {

	private static final int SAMPLE_SIZE = 1000;

	private final GeoPackage geoPackage;
	private final FeatureEntry entry;
	private final List<SourceField> fields;
	private final SourceSchema schema;

	GeoPackageSourceReader(Path file, Integer sridOverride) {
		GeoPackage pkg;
		try {
			pkg = new GeoPackage(file.toFile());
		} catch (IOException e) {
			throw new SourceReadException("GeoPackage konnte nicht geöffnet werden: " + file, e);
		}
		this.geoPackage = pkg;
		try {
			List<FeatureEntry> entries = geoPackage.features();
			if (entries.isEmpty()) {
				throw new SourceReadException("GeoPackage enthält keine Feature-Tabelle: " + file.getFileName());
			}
			this.entry = entries.get(0);
			SchemaBuild build = buildSchema(sridOverride);
			this.fields = build.fields();
			this.schema = build.schema();
		} catch (IOException e) {
			geoPackage.close();
			throw new SourceReadException("GeoPackage-Schema konnte nicht gelesen werden: " + file, e);
		} catch (RuntimeException e) {
			geoPackage.close();
			throw e;
		}
	}

	private record SchemaBuild(SourceSchema schema, List<SourceField> fields) {
	}

	private SchemaBuild buildSchema(Integer sridOverride) throws IOException {
		GeometryType declaredType = GeometryClassifier.classify(
				entry.getGeometryType() == null ? null : entry.getGeometryType().getBinding());
		CrsDetector.Detection declaredCrs = declaredCrs(sridOverride);

		GeometryType geometryType = declaredType;
		CrsDetector.Detection crs = declaredCrs;
		List<SourceField> attributeFields;

		try (SimpleFeatureReader reader = geoPackage.reader(entry, Filter.INCLUDE, Transaction.AUTO_COMMIT)) {
			SimpleFeatureType featureType = reader.getFeatureType();
			attributeFields = FeatureTypes.attributeFields(featureType);

			if (declaredType == null || declaredCrs == null) {
				FeatureSampling.Sample sample = sample(reader);
				if (declaredType == null) {
					geometryType = sample.geometryType();
				}
				if (declaredCrs == null) {
					crs = CrsDetector.guess(sample.bbox());
				}
			}
		}

		SourceSchema schema = new SourceSchema(
				geometryType == null ? GeometryType.GEOMETRY : geometryType,
				crs.srid(), attributeFields, "UTF-8", crs.confidence(), countFeatures());
		return new SchemaBuild(schema, attributeFields);
	}

	/** GeoPackage srs_id 0 and -1 are the format's own placeholders for "undefined", not real EPSG codes. */
	private CrsDetector.Detection declaredCrs(Integer sridOverride) {
		if (sridOverride != null) {
			return CrsDetector.declared(sridOverride);
		}
		Integer srid = entry.getSrid();
		if (srid != null && srid > 0) {
			return CrsDetector.declared(srid);
		}
		return null;
	}

	private static FeatureSampling.Sample sample(SimpleFeatureReader reader) throws IOException {
		List<Geometry> sampled = new ArrayList<>(SAMPLE_SIZE);
		int count = 0;
		while (reader.hasNext() && count < SAMPLE_SIZE) {
			SimpleFeature feature = reader.next();
			count++;
			if (feature.getDefaultGeometry() instanceof Geometry geometry) {
				sampled.add(geometry);
			}
		}
		return FeatureSampling.sample(sampled.iterator(), SAMPLE_SIZE);
	}

	private Long countFeatures() {
		String tableName = entry.getTableName().replace("\"", "\"\"");
		try (Connection connection = geoPackage.getDataSource().getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM \"" + tableName + "\"")) {
			return resultSet.next() ? resultSet.getLong(1) : null;
		} catch (SQLException e) {
			return null; // an unknown total is fine; the caller treats it as "not yet known"
		}
	}

	@Override
	public SourceSchema schema() {
		return schema;
	}

	@Override
	public Stream<SourceFeature> features() {
		SimpleFeatureReader reader;
		try {
			reader = geoPackage.reader(entry, Filter.INCLUDE, Transaction.AUTO_COMMIT);
		} catch (IOException e) {
			throw new SourceReadException("GeoPackage-Layer konnte nicht gelesen werden", e);
		}
		Iterator<SimpleFeature> iterator = new GeoPackageFeatureIterator(reader);
		return toSourceFeatureStream(iterator, fields).onClose(() -> closeQuietly(reader));
	}

	private static void closeQuietly(SimpleFeatureReader reader) {
		try {
			reader.close();
		} catch (IOException e) {
			// reading is already finished at this point; nothing left to do about it
		}
	}

	/**
	 * Adapts the checked-exception {@link SimpleFeatureReader} to a plain {@link Iterator}.
	 * A failure while decoding one row's geometry is recorded and skipped, since the JDBC
	 * cursor underneath has still advanced; a failure asking whether more rows exist is
	 * treated as fatal, since that means the cursor itself is broken.
	 */
	private final class GeoPackageFeatureIterator implements Iterator<SimpleFeature> {
		private final SimpleFeatureReader reader;
		private SimpleFeature pending;
		private boolean exhausted;

		GeoPackageFeatureIterator(SimpleFeatureReader reader) {
			this.reader = reader;
			advance();
		}

		private void advance() {
			pending = null;
			while (!exhausted && pending == null) {
				boolean hasMore;
				try {
					hasMore = reader.hasNext();
				} catch (IOException e) {
					throw new SourceReadException("GeoPackage-Layer konnte nicht vollständig gelesen werden", e);
				}
				if (!hasMore) {
					exhausted = true;
					return;
				}
				try {
					pending = reader.next();
				} catch (IOException e) {
					recordSkip();
				}
			}
		}

		@Override
		public boolean hasNext() {
			return pending != null;
		}

		@Override
		public SimpleFeature next() {
			if (pending == null) {
				throw new NoSuchElementException();
			}
			SimpleFeature result = pending;
			advance();
			return result;
		}
	}

	@Override
	public void close() {
		geoPackage.close();
	}
}
