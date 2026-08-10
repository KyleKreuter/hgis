package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CharsetDetectorTest {

	@ParameterizedTest
	@CsvSource({
			"UTF-8,          UTF-8",
			"utf-8,          UTF-8",
			"ISO-8859-1,     ISO-8859-1",
			"1252,           windows-1252",
			"'ANSI 1252',    windows-1252",
			"65001,          UTF-8",
	})
	@DisplayName("liest verbreitete .cpg-Inhalte als Charset")
	void parsesCommonCpgLabels(String label, String expectedCharsetName) {
		assertThat(CharsetDetector.parseCpgLabel(label).name()).isEqualToIgnoringCase(expectedCharsetName);
	}

	@Test
	@DisplayName("liefert null für einen leeren oder unlesbaren .cpg-Inhalt")
	void blankCpgLabelYieldsNull() {
		assertThat(CharsetDetector.parseCpgLabel("")).isNull();
		assertThat(CharsetDetector.parseCpgLabel("   ")).isNull();
	}

	@Test
	@DisplayName("erkennt eine reine UTF-8 CSV-Datei ohne Override als UTF-8")
	void detectsUtf8Csv(@TempDir Path dir) throws IOException {
		Path csv = dir.resolve("utf8.csv");
		Files.writeString(csv, "name;ort\nMüllerstraße;Köln\n", StandardCharsets.UTF_8);

		assertThat(CharsetDetector.detectForCsv(csv, null)).isEqualTo(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("erkennt eine Windows-1252 CSV-Datei ohne Override als windows-1252")
	void detectsWindows1252Csv(@TempDir Path dir) throws IOException {
		Path csv = dir.resolve("cp1252.csv");
		Files.writeString(csv, "name;ort\nMüllerstraße;Köln\n", CharsetDetector.WINDOWS_1252);

		assertThat(CharsetDetector.detectForCsv(csv, null)).isEqualTo(CharsetDetector.WINDOWS_1252);
	}

	@Test
	@DisplayName("respektiert einen expliziten Charset-Override für CSV")
	void honoursCsvOverride(@TempDir Path dir) throws IOException {
		Path csv = dir.resolve("any.csv");
		Files.writeString(csv, "name;ort\nMüllerstraße;Köln\n", StandardCharsets.UTF_8);

		assertThat(CharsetDetector.detectForCsv(csv, StandardCharsets.ISO_8859_1)).isEqualTo(StandardCharsets.ISO_8859_1);
	}
}
