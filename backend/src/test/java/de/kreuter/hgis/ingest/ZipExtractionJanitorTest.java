package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Checks the part of the cleanup that no unit test can (it is actually wired to run), plus
 * the age-based sweep itself -- the counterpart to {@link UploadJanitorTest} for what
 * {@code ZipExtractor} leaves behind in the system temp directory when nothing ever closed
 * the reader that owned it (see {@link ZipExtractionJanitor}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ZipExtractionJanitorTest {

	@Autowired
	private ZipExtractionJanitor janitor;

	@Autowired
	private ScheduledTaskHolder scheduledTasks;

	private final Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));

	@Test
	@DisplayName("is scheduled as a recurring task")
	void isScheduledToRunRepeatedly() {
		assertThat(scheduledTasks.getScheduledTasks())
				.extracting(ScheduledTask::toString)
				.anyMatch(task -> task.contains("ZipExtractionJanitor.cleanUpAbandonedExtractedDirectories"));
	}

	@Test
	@DisplayName("removes an extracted directory old enough that no real import could still be using it")
	void removesAnAbandonedExtractedDirectory() throws IOException {
		Path abandoned = Files.createTempDirectory(tempRoot, ZipExtractionJanitor.EXTRACTED_DIR_PREFIX);
		Files.writeString(abandoned.resolve("gebaeude.shp"), "Inhalt");
		Files.setLastModifiedTime(abandoned, FileTime.from(Instant.now().minus(UploadJanitor.MAX_AGE).minusSeconds(60)));

		janitor.cleanUpAbandonedExtractedDirectories();

		assertThat(Files.exists(abandoned)).isFalse();
	}

	@Test
	@DisplayName("leaves a freshly extracted directory alone -- it may still be in use")
	void keepsAFreshExtractedDirectory() throws IOException {
		Path active = Files.createTempDirectory(tempRoot, ZipExtractionJanitor.EXTRACTED_DIR_PREFIX);
		try {
			janitor.cleanUpAbandonedExtractedDirectories();

			assertThat(Files.exists(active)).isTrue();
		}
		finally {
			deleteQuietly(active);
		}
	}

	@Test
	@DisplayName("the startup sweep removes every extracted directory unconditionally, fresh or not")
	void removesEverythingAtStartupRegardlessOfAge() throws IOException {
		Path fresh = Files.createTempDirectory(tempRoot, ZipExtractionJanitor.EXTRACTED_DIR_PREFIX);

		janitor.cleanUpAllExtractedDirectories();

		assertThat(Files.exists(fresh)).isFalse();
	}

	private static void deleteQuietly(Path directory) {
		try (Stream<Path> walk = Files.walk(directory)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ignored) {
					// best effort -- this is test cleanup, not the behaviour under test
				}
			});
		}
		catch (IOException ignored) {
			// same reasoning
		}
	}
}
