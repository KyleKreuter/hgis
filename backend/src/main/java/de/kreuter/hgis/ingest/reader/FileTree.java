package de.kreuter.hgis.ingest.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Best-effort recursive delete for the temp directories readers extract into. */
final class FileTree {

	private FileTree() {
	}

	static void deleteQuietly(Path root) {
		if (root == null) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// best effort -- a leftover temp file is not worth failing the import over
				}
			});
		} catch (IOException ignored) {
			// same reasoning
		}
	}
}
