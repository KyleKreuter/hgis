package de.kreuter.hgis.ingest;

import de.kreuter.hgis.common.BadRequestException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Takes an uploaded file to a temporary directory of its own and hands back the path.
 *
 * The directory is per job, so a failed import never leaves files where the next one
 * might pick them up, and cleanup is a single recursive delete.
 */
@Component
public class UploadStorage {

	private static final Logger log = LoggerFactory.getLogger(UploadStorage.class);

	/** Formats SourceReaderFactory can open. Anything else is rejected before it is stored. */
	private static final Set<String> ACCEPTED = Set.of("zip", "gpkg", "geojson", "json", "csv");

	public Path store(MultipartFile file, UUID jobId) {
		String originalName = file.getOriginalFilename();
		if (originalName == null || originalName.isBlank()) {
			throw new BadRequestException("Die hochgeladene Datei hat keinen Namen");
		}
		String extension = extensionOf(originalName);
		if (!ACCEPTED.contains(extension)) {
			throw new BadRequestException("Dateiformat '" + extension
					+ "' wird nicht unterstützt. Möglich sind: ZIP mit Shapefile, GeoPackage, GeoJSON, CSV");
		}

		try {
			Path directory = Files.createTempDirectory("hgis-import-" + jobId + "-");
			// Only the extension is taken from the upload; the stem is ours, so a crafted
			// filename cannot influence where the file lands.
			Path target = directory.resolve("upload." + extension);
			file.transferTo(target);
			return target;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Upload konnte nicht gespeichert werden", ex);
		}
	}

	/** Removes the per-job directory. Failures are logged, never propagated. */
	public void cleanUp(Path uploadedFile) {
		if (uploadedFile == null) {
			return;
		}
		Path directory = uploadedFile.getParent();
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (var walk = Files.walk(directory)) {
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
}
