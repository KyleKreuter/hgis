package de.kreuter.hgis.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cleans up the directories {@code de.kreuter.hgis.ingest.reader.ZipExtractor} unpacks a
 * Shapefile ZIP into, when nothing ever closed the reader that owned one.
 *
 * <p>Every synchronous path through {@link ImportService#runImport} and
 * {@link InspectionService#inspect} now closes its reader, success or failure, which is
 * what actually deletes one of these directories -- see the reader-cleanup note on
 * {@code runImport}. What neither can cover is the process dying outright: killed, out of
 * memory, the container restarted, mid-extraction or mid-import. The directory that leaves
 * behind was never more than a local variable inside the {@code ShapefileSourceReader}
 * that created it -- unlike an upload, nothing durable ever points at it, not even a job
 * row, so no code path anywhere gets a second chance to find and remove it. This janitor is
 * that second chance.
 *
 * <p>Deliberately its own sweep over the system temp root rather than a second thing for
 * {@link UploadJanitor} to watch: moving the extraction target underneath
 * {@link UploadStorage}'s root would make {@code ingest.reader} -- which stays free of
 * Spring on purpose, so its readers are testable without a backend, see
 * {@code AbstractSourceReader}'s static initializer -- depend on a Spring-managed
 * component from the parent package. A few extra lines of near-identical sweeping code
 * here cost less than that dependency would.
 *
 * <p>Structured the same way as {@link UploadJanitor}, its closest relative: an
 * unconditional sweep at startup, plus a periodic one gated by age rather than by prefix
 * alone, so a directory currently being extracted into or read from is never touched.
 */
@Component
public class ZipExtractionJanitor {

	private static final Logger log = LoggerFactory.getLogger(ZipExtractionJanitor.class);

	/**
	 * Must track {@code ZipExtractor}'s own prefix. That class is package-private to
	 * {@code ingest.reader} on purpose (see the class Javadoc above), so this is duplicated
	 * rather than shared across the package boundary -- keep the two in sync if either
	 * changes.
	 */
	static final String EXTRACTED_DIR_PREFIX = "hgis-shp-";

	private final Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));

	/**
	 * Startup is the one moment when deleting every {@code hgis-shp-} directory is provably
	 * safe, for an even stronger reason than {@link UploadJanitor} already relies on for
	 * uploads: an upload is at least addressable by the id a client was handed, but nothing
	 * anywhere -- not a client, not a database row -- ever holds the path to one of these
	 * directories except the in-memory reader that made it. That reader cannot have
	 * survived a restart, so nothing still expects the directory to be there.
	 *
	 * <p>Like {@link UploadJanitor}, this assumes exactly one instance of the application
	 * owns this system temp directory. Nothing here enforces that; it is a deployment
	 * assumption already made by the sibling janitor, not a new one introduced here.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void cleanUpAllExtractedDirectories() {
		int removed = sweep(directory -> true);
		if (removed > 0) {
			log.info("Removed {} extracted shapefile director(ies) left over from a previous run", removed);
		}
	}

	/**
	 * Removes directories old enough that no legitimate extraction or import could still be
	 * using them. Reuses {@link UploadJanitor#MAX_AGE}: both janitors are answering the same
	 * question -- how long could a real, still-running operation possibly take -- and a
	 * single generously long threshold is easier to reason about than two barely different
	 * ones. Generous on purpose, the same way {@code ZipExtractor}'s own byte limits are:
	 * safety here means never touching a live directory, not sweeping quickly.
	 */
	@Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
	public void cleanUpAbandonedExtractedDirectories() {
		Instant cutoff = Instant.now().minus(UploadJanitor.MAX_AGE);
		int removed = sweep(directory -> lastModified(directory).isBefore(cutoff));
		if (removed > 0) {
			log.info("Removed {} extracted shapefile director(ies) abandoned by a crashed import", removed);
		}
	}

	private int sweep(Predicate<Path> selector) {
		if (!Files.isDirectory(tempRoot)) {
			return 0;
		}
		List<Path> matches;
		try (Stream<Path> entries = Files.list(tempRoot)) {
			matches = entries
					.filter(Files::isDirectory)
					.filter(path -> path.getFileName().toString().startsWith(EXTRACTED_DIR_PREFIX))
					.filter(selector)
					.toList();
		}
		catch (IOException ex) {
			log.warn("Could not list the system temp directory {}", tempRoot, ex);
			return 0;
		}
		matches.forEach(this::deleteDirectory);
		return matches.size();
	}

	private static Instant lastModified(Path directory) {
		try {
			return Files.getLastModifiedTime(directory).toInstant();
		}
		catch (IOException ex) {
			// Unreadable metadata makes the age unknowable; treating it as brand new keeps
			// this janitor from deleting a directory it cannot judge -- same reasoning as
			// UploadStorage's own lastModified.
			return Instant.now();
		}
	}

	private void deleteDirectory(Path directory) {
		try (Stream<Path> walk = Files.walk(directory)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ex) {
					log.warn("Could not delete {}", path, ex);
				}
			});
		}
		catch (IOException ex) {
			log.warn("Could not clean up extracted directory {}", directory, ex);
		}
	}
}
