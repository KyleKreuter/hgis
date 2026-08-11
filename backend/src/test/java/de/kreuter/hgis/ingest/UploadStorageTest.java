package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.ingest.UploadStorage.StoredUpload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The half of the preview that has nothing to do with reading files: an upload has to
 * still be there when the user comes back with a corrected encoding, and it has to be
 * gone when they never come back at all.
 */
class UploadStorageTest {

	private final UploadStorage storage = new UploadStorage();

	@Test
	@DisplayName("findet einen gespeicherten Upload samt Originalnamen wieder")
	void findsAStoredUploadAgain() {
		StoredUpload stored = storage.store(csvUpload("Gebäude Hamburg.csv"));
		try {
			StoredUpload found = storage.find(stored.id());

			assertThat(found.file()).isEqualTo(stored.file());
			assertThat(found.originalFilename())
					.as("the stored file keeps only the extension, so the name has to survive elsewhere")
					.isEqualTo("Gebäude Hamburg.csv");
		}
		finally {
			storage.cleanUp(stored.file());
		}
	}

	@Test
	@DisplayName("übernimmt nur die Endung in den Dateinamen")
	void takesNothingButTheExtensionFromTheUpload() {
		StoredUpload stored = storage.store(csvUpload("../../etc/passwd.csv"));
		try {
			assertThat(stored.file().getFileName()).hasToString("upload.csv");
			assertThat(stored.file().getParent().getFileName()).hasToString(stored.id().toString());
		}
		finally {
			storage.cleanUp(stored.file());
		}
	}

	@Test
	@DisplayName("ersetzt den gespeicherten Dateinamen in Meldungen durch den Originalnamen")
	void putsTheOriginalNameBackIntoMessages() {
		StoredUpload stored = storage.store(csvUpload("adressen.csv"));
		try {
			assertThat(stored.withOriginalName("CSV-Datei ist leer: upload.csv"))
					.isEqualTo("CSV-Datei ist leer: adressen.csv");
		}
		finally {
			storage.cleanUp(stored.file());
		}
	}

	@Test
	@DisplayName("meldet einen unbekannten Upload als nicht gefunden")
	void reportsAnUnknownUploadAsMissing() {
		UUID unknown = UUID.randomUUID();

		assertThatThrownBy(() -> storage.find(unknown))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining(unknown.toString());
	}

	@Test
	@DisplayName("lehnt ein nicht lesbares Format ab, bevor es gespeichert wird")
	void rejectsAnUnsupportedFormatBeforeStoringIt() {
		assertThatThrownBy(() -> storage.store(
				new MockMultipartFile("file", "bericht.pdf", "application/pdf", new byte[] {1, 2, 3})))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("pdf");
	}

	@Test
	@DisplayName("löscht nur Uploads, die älter als die Frist sind")
	void deletesOnlyUploadsPastTheirAge() throws IOException {
		StoredUpload old = storage.store(csvUpload("vergessen.csv"));
		StoredUpload fresh = storage.store(csvUpload("gerade-eben.csv"));
		try {
			Files.setLastModifiedTime(old.file().getParent(),
					FileTime.from(Instant.now().minus(Duration.ofHours(7))));

			storage.deleteOlderThan(Duration.ofHours(6));

			assertThat(Files.exists(old.file())).isFalse();
			assertThat(Files.exists(fresh.file())).isTrue();
		}
		finally {
			storage.cleanUp(old.file());
			storage.cleanUp(fresh.file());
		}
	}

	@Test
	@DisplayName("räumt beim Start restlos auf")
	void removesEverythingOnStartup() {
		StoredUpload stored = storage.store(csvUpload("uebriggeblieben.csv"));
		try {
			storage.deleteAll();

			assertThat(Files.exists(stored.file())).isFalse();
		}
		finally {
			storage.cleanUp(stored.file());
		}
	}

	private static MockMultipartFile csvUpload(String filename) {
		return new MockMultipartFile("file", filename, "text/csv",
				"x;y;name\n10;50;Test\n".getBytes(StandardCharsets.UTF_8));
	}
}
