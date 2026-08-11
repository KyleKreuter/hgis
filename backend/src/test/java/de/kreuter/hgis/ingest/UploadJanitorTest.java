package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.ingest.UploadStorage.StoredUpload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Checks the part of the cleanup that no unit test can: that it is actually wired to run.
 *
 * An inspection creates no job, so nothing in the database points at an abandoned upload
 * -- a sweep that is written but never scheduled would leave half a gigabyte per closed
 * dialog behind, and nothing would report it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UploadJanitorTest {

	@Autowired
	private UploadJanitor janitor;

	@Autowired
	private UploadStorage uploadStorage;

	@Autowired
	private ScheduledTaskHolder scheduledTasks;

	@Test
	@DisplayName("ist als wiederkehrende Aufgabe eingeplant")
	void isScheduledToRunRepeatedly() {
		assertThat(scheduledTasks.getScheduledTasks())
				.extracting(ScheduledTask::toString)
				.anyMatch(task -> task.contains("UploadJanitor.cleanUpExpiredUploads"));
	}

	@Test
	@DisplayName("entfernt einen Upload, den niemand importiert hat")
	void removesAnUploadNobodyEverImported() throws IOException {
		StoredUpload abandoned = uploadStorage.store(new MockMultipartFile(
				"file", "nie-importiert.csv", "text/csv",
				"x;y;name\n10;50;Test\n".getBytes(StandardCharsets.UTF_8)));
		Files.setLastModifiedTime(abandoned.file().getParent(),
				FileTime.from(Instant.now().minus(UploadJanitor.MAX_AGE).minusSeconds(60)));

		janitor.cleanUpExpiredUploads();

		assertThat(Files.exists(abandoned.file())).isFalse();
	}
}
