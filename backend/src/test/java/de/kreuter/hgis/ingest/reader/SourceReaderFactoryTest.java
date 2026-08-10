package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceReaderFactoryTest {

	@Test
	@DisplayName("lehnt unbekannte Dateiendungen ab")
	void rejectsUnknownExtension(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("daten.txt");
		Files.writeString(file, "irrelevant");

		assertThatThrownBy(() -> SourceReaderFactory.open(file, null, null))
				.isInstanceOf(UnsupportedSourceFormatException.class);
	}
}
