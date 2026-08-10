package de.kreuter.hgis.ingest.reader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts a Shapefile ZIP into a private temp directory so GeoTools can open the .shp
 * with an ordinary file path -- reading a multi-file format straight out of a ZIP stream
 * is not something the shapefile datastore supports.
 */
final class ZipExtractor {

	private ZipExtractor() {
	}

	static Path extract(Path zipFile) {
		try {
			Path targetDir = Files.createTempDirectory("hgis-shp-");
			try (ZipFile zip = new ZipFile(zipFile.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					Path target = safeResolve(targetDir, entry.getName());
					if (entry.isDirectory()) {
						Files.createDirectories(target);
						continue;
					}
					Files.createDirectories(target.getParent());
					try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
						in.transferTo(out);
					}
				}
			}
			return targetDir;
		} catch (IOException e) {
			throw new SourceReadException("ZIP konnte nicht entpackt werden: " + zipFile, e);
		}
	}

	/** Rejects entries that would escape the target directory ("Zip Slip"). */
	private static Path safeResolve(Path targetDir, String entryName) {
		Path resolved = targetDir.resolve(entryName).normalize();
		if (!resolved.startsWith(targetDir)) {
			throw new SourceReadException("Unsicherer Pfad im ZIP: " + entryName);
		}
		return resolved;
	}
}
