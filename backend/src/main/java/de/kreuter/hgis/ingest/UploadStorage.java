package de.kreuter.hgis.ingest;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Takes an uploaded file to a directory of its own and hands back a handle for it.
 *
 * The directory is per upload, so a failed import never leaves files where the next one
 * might pick them up, and cleanup is a single recursive delete.
 *
 * <p>An upload outlives the request that brought it in: the inspection dialog lets the
 * user change encoding or CRS and inspect again, and at the 500 MB the application
 * accepts, a second inspection must not mean a second upload. That is why the directory
 * name is the upload id itself rather than a random temp name -- it is the only way back
 * to a file whose owner is holding nothing but a UUID. {@link UploadJanitor} removes what
 * is never claimed.
 */
@Component
public class UploadStorage {

	private static final Logger log = LoggerFactory.getLogger(UploadStorage.class);

	/** Formats SourceReaderFactory can open. Anything else is rejected before it is stored. */
	private static final Set<String> ACCEPTED = Set.of("zip", "gpkg", "geojson", "json", "csv");

	/** Stem of every stored payload file; only the extension comes from the upload. */
	private static final String PAYLOAD_STEM = "upload.";

	/** Sidecar holding the name the user uploaded under, which the payload file does not keep. */
	private static final String ORIGINAL_NAME_FILE = "original-name";

	private final Path root = Path.of(System.getProperty("java.io.tmpdir"), "hgis-uploads");

	/**
	 * A stored upload and everything needed to talk about it: the id the client refers to
	 * it by, the file on disk, and the name it arrived under.
	 */
	public record StoredUpload(UUID id, Path file, String originalFilename) {

		/**
		 * Readers quote the file they were handed, and that file is stored under a name of
		 * our own making. The user would therefore read about an "upload.csv" they never
		 * named. Swapping the stored name back for theirs happens here, in the one place
		 * that knows both -- the readers keep working with the path they were given.
		 */
		public String withOriginalName(String message) {
			if (message == null || originalFilename == null || originalFilename.isBlank()) {
				return message;
			}
			return message.replace(file.getFileName().toString(), originalFilename);
		}
	}

	public StoredUpload store(MultipartFile file) {
		String originalName = file.getOriginalFilename();
		if (originalName == null || originalName.isBlank()) {
			throw new BadRequestException("Die hochgeladene Datei hat keinen Namen");
		}
		String extension = extensionOf(originalName);
		if (!ACCEPTED.contains(extension)) {
			throw new BadRequestException("Der Import unterstützt das Dateiformat '" + extension
					+ "' nicht. Möglich sind: ZIP mit Shapefile, GeoPackage, GeoJSON, CSV.");
		}

		UUID uploadId = UUID.randomUUID();
		try {
			Path directory = Files.createDirectories(root.resolve(uploadId.toString()));
			// Only the extension is taken from the upload; the stem is ours, so a crafted
			// filename cannot influence where the file lands.
			Path target = directory.resolve(PAYLOAD_STEM + extension);
			file.transferTo(target);
			Files.writeString(directory.resolve(ORIGINAL_NAME_FILE), originalName, StandardCharsets.UTF_8);
			return new StoredUpload(uploadId, target, originalName);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Der Import kann den Upload nicht speichern", ex);
		}
	}

	/**
	 * @throws NotFoundException when the upload was never stored or has already been
	 *         cleaned up -- the client has to send the file again, and telling them so
	 *         beats a stack trace about a missing path
	 */
	public StoredUpload find(UUID uploadId) {
		Path directory = root.resolve(uploadId.toString());
		Optional<Path> payload = findPayload(directory);
		if (payload.isEmpty()) {
			throw new NotFoundException("Der Upload " + uploadId
					+ " ist nicht mehr vorhanden. Bitte laden Sie die Datei erneut hoch.");
		}
		return new StoredUpload(uploadId, payload.get(), readOriginalName(directory, payload.get()));
	}

	/** Removes the per-upload directory. Failures are logged, never propagated. */
	public void cleanUp(Path uploadedFile) {
		if (uploadedFile == null) {
			return;
		}
		deleteDirectory(uploadedFile.getParent());
	}

	/**
	 * Removes every stored upload. Only ever correct at startup: an upload is reachable
	 * solely through the id handed out during the request that stored it, so nothing that
	 * survived a restart is still being worked on.
	 *
	 * @return number of upload directories removed
	 */
	public int deleteAll() {
		return deleteMatching(directory -> true);
	}

	/**
	 * Removes uploads older than {@code maxAge}, measured from the last write into their
	 * directory -- which is the moment the file arrived, since nothing writes there again.
	 * Inspecting an upload therefore does not extend its life; the age has to be generous
	 * enough for a user who leaves the dialog open, not for one who leaves for the day.
	 *
	 * @return number of upload directories removed
	 */
	public int deleteOlderThan(Duration maxAge) {
		Instant cutoff = Instant.now().minus(maxAge);
		return deleteMatching(directory -> lastModified(directory).isBefore(cutoff));
	}

	public static String extensionOf(String filename) {
		int dot = filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/** File name without extension -- the default layer name when none is given. */
	public static String baseNameOf(String filename) {
		String withoutPath = filename.replaceAll(".*[/\\\\]", "");
		int dot = withoutPath.lastIndexOf('.');
		return dot <= 0 ? withoutPath : withoutPath.substring(0, dot);
	}

	// --- internals ---------------------------------------------------------------------

	private Optional<Path> findPayload(Path directory) {
		if (!Files.isDirectory(directory)) {
			return Optional.empty();
		}
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(path -> path.getFileName().toString().startsWith(PAYLOAD_STEM)).findFirst();
		}
		catch (IOException ex) {
			log.warn("Could not read upload directory {}", directory, ex);
			return Optional.empty();
		}
	}

	/** Falls back to the stored name, so a lost sidecar costs a nice message, not the upload. */
	private static String readOriginalName(Path directory, Path payload) {
		Path sidecar = directory.resolve(ORIGINAL_NAME_FILE);
		try {
			return Files.exists(sidecar)
					? Files.readString(sidecar, StandardCharsets.UTF_8)
					: payload.getFileName().toString();
		}
		catch (IOException ex) {
			log.warn("Could not read original file name for upload {}", directory.getFileName(), ex);
			return payload.getFileName().toString();
		}
	}

	private int deleteMatching(Predicate<Path> selector) {
		if (!Files.isDirectory(root)) {
			return 0;
		}
		List<Path> directories;
		try (Stream<Path> entries = Files.list(root)) {
			directories = entries.filter(Files::isDirectory).filter(selector).toList();
		}
		catch (IOException ex) {
			log.warn("Could not list upload root {}", root, ex);
			return 0;
		}
		directories.forEach(this::deleteDirectory);
		return directories.size();
	}

	private static Instant lastModified(Path directory) {
		try {
			return Files.getLastModifiedTime(directory).toInstant();
		}
		catch (IOException ex) {
			// Unreadable metadata makes the age unknowable; treating it as brand new keeps
			// the janitor from deleting a file it cannot judge.
			return Instant.now();
		}
	}

	private void deleteDirectory(Path directory) {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
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
			log.warn("Could not clean up upload directory {}", directory, ex);
		}
	}
}
