package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

class GeoPackageSourceReaderTest {

	@Test
	@DisplayName("liest Geometrie, Attribute und die deklarierte CRS aus der SRS-Tabelle")
	void readsFeaturesAndDeclaredCrs(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("layer.gpkg");
		writeGeoPackage(file);

		try (SourceReader reader = SourceReaderFactory.open(file, null, null)) {
			SourceSchema schema = reader.schema();
			assertThat(schema.geometryType()).isEqualTo(SourceSchema.GeometryType.MULTIPOINT);
			assertThat(schema.sourceSrid()).isEqualTo(4326);
			assertThat(schema.crsConfidence()).isEqualTo(SourceSchema.CrsConfidence.DECLARED);
			assertThat(schema.featureCount()).isEqualTo(1L);

			List<SourceFeature> features = readAll(reader);
			assertThat(features).hasSize(1);
			assertThat(features.get(0).attributes().get("name")).isEqualTo("Testpunkt");
		}
	}

	@Test
	@DisplayName("verwendet den SRID-Override statt die SRS-Tabelle")
	void honoursSridOverride(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("override.gpkg");
		writeGeoPackage(file);

		try (SourceReader reader = SourceReaderFactory.open(file, 25832, null)) {
			assertThat(reader.schema().sourceSrid()).isEqualTo(25832);
			assertThat(reader.schema().crsConfidence()).isEqualTo(SourceSchema.CrsConfidence.DECLARED);
		}
	}

	private static void writeGeoPackage(Path file) throws Exception {
		SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
		builder.setName("test");
		builder.setCRS(CRS.decode("EPSG:4326"));
		builder.add("geom", Point.class);
		builder.add("name", String.class);
		SimpleFeatureType featureType = builder.buildFeatureType();

		GeometryFactory geometryFactory = new GeometryFactory();
		SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
		featureBuilder.add(geometryFactory.createPoint(new Coordinate(10.0, 53.5)));
		featureBuilder.add("Testpunkt");
		SimpleFeature feature = featureBuilder.buildFeature("1");

		ListFeatureCollection collection = new ListFeatureCollection(featureType);
		collection.add(feature);

		try (GeoPackage geoPackage = new GeoPackage(file.toFile())) {
			geoPackage.init();
			FeatureEntry entry = new FeatureEntry();
			entry.setTableName("test");
			entry.setBounds(new ReferencedEnvelope(9.9, 10.1, 53.4, 53.6, featureType.getCoordinateReferenceSystem()));
			geoPackage.add(entry, collection); // creates the table itself; an explicit create() first would double-create it
		}
	}

	/** {@link SourceReader#features()} must have its stream closed by the caller (per its JavaDoc). */
	private static List<SourceFeature> readAll(SourceReader reader) {
		try (Stream<SourceFeature> features = reader.features()) {
			return features.toList();
		}
	}
}
