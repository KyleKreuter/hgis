package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.locationtech.jts.geom.Point;

class CsvSourceReaderTest {

	@Test
	@DisplayName("liest Semikolon-getrennte CSV mit Dezimalkomma korrekt")
	void readsSemicolonWithDecimalComma(@TempDir Path dir) throws IOException {
		Path csv = writeCsv(dir, "punkte.csv", """
				rechtswert;hochwert;name;flaeche
				565000,25;5931000,50;Alster;18,4
				566100,00;5930500,75;Elbe;120,0
				""");

		try (SourceReader reader = SourceReaderFactory.open(csv, null, null)) {
			List<SourceFeature> features = readAll(reader);
			assertThat(features).hasSize(2);

			SourceFeature first = features.get(0);
			Point point = (Point) first.geometry();
			assertThat(point.getX()).isEqualTo(565000.25);
			assertThat(point.getY()).isEqualTo(5931000.50);
			assertThat(first.attributes().get("name")).isEqualTo("Alster");
			assertThat(first.attributes().get("flaeche")).isEqualTo(18.4);

			assertThat(reader.schema().crsConfidence()).isEqualTo(SourceSchema.CrsConfidence.GUESSED);
			assertThat(reader.schema().sourceSrid()).isEqualTo(25832);
		}
	}

	@Test
	@DisplayName("erkennt eine WKT-Spalte als Geometriequelle")
	void readsWktColumn(@TempDir Path dir) throws IOException {
		Path csv = writeCsv(dir, "wkt.csv", """
				name;wkt
				Punkt A;POINT (10 53.5)
				Punkt B;POINT (10.5 53.6)
				""");

		try (SourceReader reader = SourceReaderFactory.open(csv, null, null)) {
			List<SourceFeature> features = readAll(reader);
			assertThat(features).hasSize(2);
			assertThat(reader.schema().geometryType()).isEqualTo(GeometryType.MULTIPOINT);
			assertThat(features.get(0).attributes()).containsOnlyKeys("name");
		}
	}

	@Test
	@DisplayName("erkennt komma-getrennte CSV mit Dezimalpunkt")
	void readsCommaDelimitedWithDotDecimal(@TempDir Path dir) throws IOException {
		Path csv = writeCsv(dir, "comma.csv", """
				x,y,name
				10.0,53.5,Hamburg
				10.5,53.6,Norderstedt
				""");

		try (SourceReader reader = SourceReaderFactory.open(csv, null, null)) {
			List<SourceFeature> features = readAll(reader);
			assertThat(features).hasSize(2);
			Point point = (Point) features.get(0).geometry();
			assertThat(point.getX()).isEqualTo(10.0);
			assertThat(point.getY()).isEqualTo(53.5);
		}
	}

	@Test
	@DisplayName("wirft, wenn keine Geometriespalte erkennbar ist")
	void failsWithoutGeometryColumn(@TempDir Path dir) throws IOException {
		Path csv = writeCsv(dir, "noGeom.csv", """
				name;wert
				Test;1
				""");

		assertThatThrownBy(() -> SourceReaderFactory.open(csv, null, null))
				.isInstanceOf(SourceReadException.class);
	}

	@Test
	@DisplayName("überspringt Zeilen mit falscher Spaltenzahl statt den Import abzubrechen")
	void skipsMalformedRows(@TempDir Path dir) throws IOException {
		Path csv = writeCsv(dir, "broken.csv", """
				x;y;name
				10.0;53.5;Hamburg
				kaputte;zeile
				10.5;53.6;Norderstedt
				""");

		try (SourceReader reader = SourceReaderFactory.open(csv, null, null)) {
			List<SourceFeature> features = readAll(reader);
			assertThat(features).hasSize(2);
			assertThat(reader.skippedCount()).isEqualTo(1);
		}
	}

	private static Path writeCsv(Path dir, String name, String content) throws IOException {
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
