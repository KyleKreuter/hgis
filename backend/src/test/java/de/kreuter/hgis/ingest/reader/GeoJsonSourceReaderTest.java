package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeoJsonSourceReaderTest {

	@Test
	@DisplayName("nimmt EPSG:4326 an, wenn die Koordinaten dazu passen")
	void assumesWgs84ForPlausibleCoordinates(@TempDir Path dir) throws IOException {
		Path file = writeGeoJson(dir, "hamburg.geojson", """
				{"type":"FeatureCollection","features":[
				  {"type":"Feature","geometry":{"type":"Point","coordinates":[10.0,53.5]},"properties":{"name":"Hamburg"}}
				]}""");

		try (SourceReader reader = SourceReaderFactory.open(file, null, null)) {
			assertThat(reader.schema().sourceSrid()).isEqualTo(4326);
			assertThat(reader.schema().crsConfidence()).isEqualTo(SourceSchema.CrsConfidence.ASSUMED);
		}
	}

	@Test
	@DisplayName("meldet UTM-Koordinaten ohne CRS-Angabe als GUESSED statt sie stumm als 4326 zu lesen")
	void guessesUtmCoordinatesInsteadOfAssumingWgs84(@TempDir Path dir) throws IOException {
		Path file = writeGeoJson(dir, "utm.geojson", """
				{"type":"FeatureCollection","features":[
				  {"type":"Feature","geometry":{"type":"Point","coordinates":[565000,5931000]},"properties":{"name":"Ohne CRS"}}
				]}""");

		try (SourceReader reader = SourceReaderFactory.open(file, null, null)) {
			SourceSchema schema = reader.schema();
			assertThat(schema.crsConfidence()).isEqualTo(SourceSchema.CrsConfidence.GUESSED);
			assertThat(schema.sourceSrid()).isEqualTo(25832);
		}
	}

	@Test
	@DisplayName("liest das veraltete crs-Element als DECLARED")
	void readsLegacyCrsMember(@TempDir Path dir) throws IOException {
		Path file = writeGeoJson(dir, "legacy.geojson", """
				{"type":"FeatureCollection",
				 "crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:EPSG::25832"}},
				 "features":[
				  {"type":"Feature","geometry":{"type":"Point","coordinates":[565000,5931000]},"properties":{"name":"Legacy"}}
				]}""");

		try (SourceReader reader = SourceReaderFactory.open(file, null, null)) {
			SourceSchema schema = reader.schema();
			assertThat(schema.crsConfidence()).isEqualTo(SourceSchema.CrsConfidence.DECLARED);
			assertThat(schema.sourceSrid()).isEqualTo(25832);
		}
	}

	@Test
	@DisplayName("liest Geometrie und Attribute je Feature korrekt")
	void readsFeaturesWithProperties(@TempDir Path dir) throws IOException {
		Path file = writeGeoJson(dir, "attrs.geojson", """
				{"type":"FeatureCollection","features":[
				  {"type":"Feature","geometry":{"type":"Point","coordinates":[10.0,53.5]},
				   "properties":{"name":"Alster","flaeche":18.4,"anzahl":3}}
				]}""");

		try (SourceReader reader = SourceReaderFactory.open(file, null, null)) {
			List<SourceFeature> features = readAll(reader);
			assertThat(features).hasSize(1);
			SourceFeature feature = features.get(0);
			assertThat(feature.attributes().get("name")).isEqualTo("Alster");
			assertThat(feature.attributes().get("flaeche")).isEqualTo(18.4);
			assertThat(feature.attributes().get("anzahl")).isEqualTo(3L);
			assertThat(reader.schema().geometryType()).isEqualTo(GeometryType.MULTIPOINT);
		}
	}

	@Test
	@DisplayName("meldet einen SRID-Override als DECLARED, auch ohne crs-Element")
	void honoursSridOverride(@TempDir Path dir) throws IOException {
		Path file = writeGeoJson(dir, "override.geojson", """
				{"type":"FeatureCollection","features":[
				  {"type":"Feature","geometry":{"type":"Point","coordinates":[10.0,53.5]},"properties":{}}
				]}""");

		try (SourceReader reader = SourceReaderFactory.open(file, 4258, null)) {
			assertThat(reader.schema().sourceSrid()).isEqualTo(4258);
			assertThat(reader.schema().crsConfidence()).isEqualTo(SourceSchema.CrsConfidence.DECLARED);
		}
	}

	private static Path writeGeoJson(Path dir, String name, String content) throws IOException {
		Path file = dir.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	/** {@link SourceReader#features()} must have its stream closed by the caller (per its JavaDoc). */
	private static List<SourceFeature> readAll(SourceReader reader) {
		try (Stream<SourceFeature> features = reader.features()) {
			return features.toList();
		}
	}
}
