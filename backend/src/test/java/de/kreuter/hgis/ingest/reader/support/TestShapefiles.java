package de.kreuter.hgis.ingest.reader.support;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.FeatureWriter;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

/**
 * Writes small Shapefile sets for tests using GeoTools itself, then optionally patches the
 * DBF header or removes the .cpg file to exercise every branch of the charset detection
 * order (.cpg, LDID byte, content sniffing).
 */
public final class TestShapefiles {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

	private TestShapefiles() {
	}

	public enum CharsetHint {
		/** A .cpg file states the encoding; the DBF's own LDID byte is deliberately wrong. */
		CPG_FILE,
		/** No .cpg; the DBF header's language driver ID byte states the encoding. */
		LDID_BYTE,
		/** Neither .cpg nor a usable LDID byte; the reader must sniff the content. */
		SNIFF_ONLY
	}

	/**
	 * Writes a one-point Shapefile whose "name" attribute holds {@code value}, encoded as
	 * Windows-1252 in the DBF, with the given hint for how (or whether) that encoding is
	 * announced to a reader.
	 */
	public static Path writeWithAttributeValue(Path dir, String baseName, String value, CharsetHint hint) throws IOException {
		Path shpFile = dir.resolve(baseName + ".shp");
		write(shpFile, org.locationtech.jts.geom.Point.class, "name", String.class, List.<Object[]>of(
				new Object[] {GEOMETRY_FACTORY.createPoint(new Coordinate(10, 50)), value}));

		Path dbf = dir.resolve(baseName + ".dbf");
		Path cpg = dir.resolve(baseName + ".cpg");
		switch (hint) {
			case CPG_FILE -> {
				Files.writeString(cpg, "windows-1252");
				patchLdid(dbf, (byte) 0x01); // IBM437 -- deliberately wrong, .cpg must win
			}
			case LDID_BYTE -> {
				Files.deleteIfExists(cpg);
				patchLdid(dbf, (byte) 0x03); // ANSI / Windows-1252
			}
			case SNIFF_ONLY -> {
				Files.deleteIfExists(cpg);
				patchLdid(dbf, (byte) 0x00); // unset -- nothing to trust but the content
			}
		}
		return shpFile;
	}

	/** Writes a polygon Shapefile with one plain-ring and one multi-ring (MultiPolygon) feature. */
	public static Path writeMixedPolygons(Path dir, String baseName) throws IOException {
		Path shpFile = dir.resolve(baseName + ".shp");
		Geometry singleRing = GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
				new Coordinate(0, 0), new Coordinate(0, 1), new Coordinate(1, 1), new Coordinate(1, 0), new Coordinate(0, 0)
		});
		Geometry multiRing = GEOMETRY_FACTORY.createMultiPolygon(new org.locationtech.jts.geom.Polygon[] {
				GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
						new Coordinate(2, 2), new Coordinate(2, 3), new Coordinate(3, 3), new Coordinate(3, 2), new Coordinate(2, 2)
				}),
				GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
						new Coordinate(5, 5), new Coordinate(5, 6), new Coordinate(6, 6), new Coordinate(6, 5), new Coordinate(5, 5)
				})
		});
		// Both a plain Polygon and a genuine MultiPolygon value are written under a schema
		// declared as MultiPolygon -- GeoTools' shapefile writer promotes the single ring
		// set to a one-element multi geometry, exactly as the "Polygon" ESRI shape type
		// natively allows either.
		write(shpFile, org.locationtech.jts.geom.MultiPolygon.class, "name", String.class, List.of(
				new Object[] {singleRing, "einzel"},
				new Object[] {multiRing, "mehrfach"}));
		return shpFile;
	}

	private static void write(Path shpFile, Class<?> geometryType, String attributeName, Class<?> attributeType,
			List<Object[]> rows) throws IOException {
		Map<String, Object> params = new HashMap<>();
		params.put(ShapefileDataStoreFactory.URLP.key, shpFile.toUri().toURL());
		params.put(ShapefileDataStoreFactory.DBFCHARSET.key, Charset.forName("windows-1252"));

		DataStore dataStore = new ShapefileDataStoreFactory().createNewDataStore(params);
		try {
			SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
			builder.setName("test");
			builder.add("the_geom", geometryType);
			builder.add(attributeName, attributeType);
			SimpleFeatureType featureType = builder.buildFeatureType();
			dataStore.createSchema(featureType);

			String typeName = dataStore.getTypeNames()[0];
			try (FeatureWriter<SimpleFeatureType, SimpleFeature> writer =
					dataStore.getFeatureWriterAppend(typeName, Transaction.AUTO_COMMIT)) {
				for (Object[] row : rows) {
					SimpleFeature feature = writer.next();
					feature.setAttribute("the_geom", row[0]);
					feature.setAttribute(attributeName, row[1]);
					writer.write();
				}
			}
		} finally {
			dataStore.dispose();
		}
	}

	private static void patchLdid(Path dbfFile, byte ldid) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(dbfFile.toFile(), "rw")) {
			raf.seek(29);
			raf.write(ldid);
		}
	}
}
