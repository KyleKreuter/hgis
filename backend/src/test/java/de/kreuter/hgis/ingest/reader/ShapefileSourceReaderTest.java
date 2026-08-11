package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.ingest.reader.support.TestShapefiles;
import de.kreuter.hgis.ingest.reader.support.TestShapefiles.CharsetHint;
import de.kreuter.hgis.ingest.reader.support.TestZips;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ShapefileSourceReaderTest {

	@ParameterizedTest
	@EnumSource(CharsetHint.class)
	@DisplayName("liest Umlaute unabhängig davon, wie die DBF-Kodierung angegeben ist")
	void readsUmlautsRegardlessOfCharsetHint(CharsetHint hint, @TempDir Path dir) throws Exception {
		Path shp = TestShapefiles.writeWithAttributeValue(dir, "strassen", "Müllerstraße", hint);
		Path zip = TestZips.zipShapefileSet(shp, dir.resolve("strassen.zip"));

		try (SourceReader reader = SourceReaderFactory.open(zip, null, null)) {
			List<SourceFeature> features = readAll(reader);
			assertThat(features).hasSize(1);
			assertThat(features.get(0).attributes().get("name")).isEqualTo("Müllerstraße");
			assertThat(reader.skippedCount()).isZero();
		}
	}

	@Test
	@DisplayName("meldet gemischte Polygon-/Multipolygon-Geometrien als MULTIPOLYGON")
	void mixedPolygonsBecomeMultipolygon(@TempDir Path dir) throws Exception {
		Path shp = TestShapefiles.writeMixedPolygons(dir, "flaechen");
		Path zip = TestZips.zipShapefileSet(shp, dir.resolve("flaechen.zip"));

		try (SourceReader reader = SourceReaderFactory.open(zip, null, null)) {
			SourceSchema schema = reader.schema();
			assertThat(schema.geometryType()).isEqualTo(GeometryType.MULTIPOLYGON);
			assertThat(readAll(reader)).hasSize(2);
		}
	}

	@Test
	@DisplayName("verwendet den SRID-Override statt eine CRS-Vermutung anzustellen")
	void honoursSridOverride(@TempDir Path dir) throws Exception {
		Path shp = TestShapefiles.writeWithAttributeValue(dir, "punkte", "Test", CharsetHint.SNIFF_ONLY);
		Path zip = TestZips.zipShapefileSet(shp, dir.resolve("punkte.zip"));

		try (SourceReader reader = SourceReaderFactory.open(zip, 25832, null)) {
			assertThat(reader.schema().sourceSrid()).isEqualTo(25832);
			assertThat(reader.schema().crsConfidence()).isEqualTo(SourceSchema.CrsConfidence.DECLARED);
		}
	}

	@Test
	@DisplayName("verwendet den Charset-Override statt eine Erkennung anzustellen")
	void honoursCharsetOverride(@TempDir Path dir) throws Exception {
		// Written with windows-1252 bytes but with no way for the reader to detect that on
		// its own; forcing UTF-8 must produce mojibake, proving the override really applies
		// instead of silently falling back to detection.
		Path shp = TestShapefiles.writeWithAttributeValue(dir, "override", "Straße", CharsetHint.SNIFF_ONLY);
		Path zip = TestZips.zipShapefileSet(shp, dir.resolve("override.zip"));

		try (SourceReader reader = SourceReaderFactory.open(zip, null, java.nio.charset.StandardCharsets.US_ASCII)) {
			assertThat(reader.schema().charset()).isEqualTo("US-ASCII");
		}
	}

	/** {@link SourceReader#features()} must have its stream closed by the caller (per its JavaDoc). */
	private static List<SourceFeature> readAll(SourceReader reader) {
		try (Stream<SourceFeature> features = reader.features()) {
			return features.toList();
		}
	}
}
