package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import de.kreuter.hgis.ingest.spi.SourceSchema.GeometryType;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.Query;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.FeatureIterator;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

/**
 * Reads a Shapefile set delivered as a ZIP. Shapefiles fix their geometry type at the
 * format level (Point, PolyLine or Polygon, never mixed within one file), so the declared
 * {@link SimpleFeatureType} binding is trusted outright -- no sampling needed, and a
 * Polygon-shapetype file with a mix of single- and multi-ring records still comes out as
 * MULTIPOLYGON, matching the writing side's "always the multi variant" rule.
 */
final class ShapefileSourceReader extends AbstractSourceReader {

	private static final int SAMPLE_SIZE = 1000;

	private final Path extractedDir;
	private final DataStore dataStore;
	private final String typeName;
	private final List<SourceField> fields;
	private final SourceSchema schema;

	ShapefileSourceReader(Path zipFile, Integer sridOverride, Charset charsetOverride) {
		this.extractedDir = ZipExtractor.extract(zipFile);
		DataStore ds = null;
		try {
			Path shpFile = findShpFile(extractedDir);
			Charset charset = CharsetDetector.detectForShapefile(shpFile, charsetOverride);

			Map<String, Object> params = new HashMap<>();
			params.put(ShapefileDataStoreFactory.URLP.key, shpFile.toUri().toURL());
			params.put(ShapefileDataStoreFactory.DBFCHARSET.key, charset);
			ds = new ShapefileDataStoreFactory().createDataStore(params);
			this.dataStore = ds;
			this.typeName = dataStore.getTypeNames()[0];

			SimpleFeatureSource source = dataStore.getFeatureSource(typeName);
			SimpleFeatureType featureType = source.getSchema();
			this.fields = FeatureTypes.attributeFields(featureType);
			this.schema = buildSchema(source, featureType, sridOverride, charset.name());
		} catch (IOException e) {
			if (ds != null) {
				ds.dispose();
			}
			FileTree.deleteQuietly(extractedDir);
			throw new SourceReadException("Shapefile konnte nicht gelesen werden: " + zipFile, e);
		} catch (RuntimeException e) {
			if (ds != null) {
				ds.dispose();
			}
			FileTree.deleteQuietly(extractedDir);
			throw e;
		}
	}

	private SourceSchema buildSchema(SimpleFeatureSource source, SimpleFeatureType featureType, Integer sridOverride,
			String charsetName) throws IOException {
		GeometryType geometryType = GeometryClassifier.classify(featureType.getGeometryDescriptor().getType().getBinding());

		CrsDetector.Detection crs;
		if (sridOverride != null) {
			crs = CrsDetector.declared(sridOverride);
		} else {
			crs = detectPrjCrs(featureType.getCoordinateReferenceSystem())
					.orElseGet(() -> CrsDetector.guess(sampleBbox(source)));
		}

		int count = source.getCount(Query.ALL);
		Long featureCount = count < 0 ? null : (long) count;

		return new SourceSchema(
				geometryType == null ? GeometryType.GEOMETRY : geometryType,
				crs.srid(), fields, charsetName, crs.confidence(), featureCount);
	}

	/** Empty when there is no .prj, or its CRS cannot be matched to a known EPSG code. */
	private static Optional<CrsDetector.Detection> detectPrjCrs(CoordinateReferenceSystem crs) {
		if (crs == null) {
			return Optional.empty();
		}
		try {
			Integer epsg = CRS.lookupEpsgCode(crs, true);
			return epsg == null ? Optional.empty() : Optional.of(CrsDetector.declared(epsg));
		} catch (FactoryException e) {
			return Optional.empty();
		}
	}

	private Envelope sampleBbox(SimpleFeatureSource source) {
		try (FeatureIterator<SimpleFeature> iterator = source.getFeatures().features()) {
			Iterator<Geometry> geometries = new Iterator<>() {
				@Override
				public boolean hasNext() {
					return iterator.hasNext();
				}

				@Override
				public Geometry next() {
					Object geometry = iterator.next().getDefaultGeometry();
					return geometry instanceof Geometry g ? g : null;
				}
			};
			return FeatureSampling.sample(geometries, SAMPLE_SIZE).bbox();
		} catch (IOException e) {
			throw new SourceReadException("Beispiel-Features konnten nicht gelesen werden", e);
		}
	}

	private static Path findShpFile(Path dir) {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp"))
					.findFirst()
					.orElseThrow(() -> new SourceReadException("ZIP enthält keine .shp-Datei"));
		} catch (IOException e) {
			throw new SourceReadException("ZIP konnte nicht durchsucht werden: " + dir, e);
		}
	}

	@Override
	public SourceSchema schema() {
		return schema;
	}

	@Override
	public Stream<SourceFeature> features() {
		try {
			SimpleFeatureSource source = dataStore.getFeatureSource(typeName);
			FeatureIterator<SimpleFeature> iterator = source.getFeatures().features();
			Iterator<SimpleFeature> it = new Iterator<>() {
				@Override
				public boolean hasNext() {
					return iterator.hasNext();
				}

				@Override
				public SimpleFeature next() {
					return iterator.next();
				}
			};
			return toSourceFeatureStream(it, fields).onClose(iterator::close);
		} catch (IOException e) {
			throw new SourceReadException("Shapefile-Features konnten nicht gelesen werden", e);
		}
	}

	@Override
	public void close() {
		dataStore.dispose();
		FileTree.deleteQuietly(extractedDir);
	}
}
