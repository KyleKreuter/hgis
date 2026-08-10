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
 *
 * <p>Unpacking is bounded in three ways (plan section A.9), because the size of an archive
 * says nothing about the size of its contents: a few hundred kilobytes of zeros expand to
 * gigabytes, and an unbounded extractor turns that into a full disk.
 */
final class ZipExtractor {

	/** A shapefile set is .shp/.shx/.dbf plus a handful of siblings. Anything beyond this
	 *  is not the archive we were promised. */
	private static final int MAX_ENTRIES = 100;

	/** Total unpacked bytes. Generous for real data, far below what a bomb aims for. */
	private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

	/** Bytes per single entry, so one huge member cannot exhaust the whole budget alone. */
	private static final long MAX_ENTRY_BYTES = 1L * 1024 * 1024 * 1024;

	private ZipExtractor() {
	}

	static Path extract(Path zipFile) {
		try {
			Path targetDir = Files.createTempDirectory("hgis-shp-");
			long totalBytes = 0;
			int entryCount = 0;

			try (ZipFile zip = new ZipFile(zipFile.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();

					if (++entryCount > MAX_ENTRIES) {
						throw new SourceReadException(
								"Das ZIP enthält mehr als " + MAX_ENTRIES + " Einträge");
					}

					Path target = safeResolve(targetDir, entry.getName());
					if (entry.isDirectory()) {
						Files.createDirectories(target);
						continue;
					}
					Files.createDirectories(target.getParent());

					try (InputStream in = zip.getInputStream(entry);
							OutputStream out = Files.newOutputStream(target)) {
						totalBytes += copyBounded(in, out, entry.getName(),
								Math.min(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - totalBytes));
					}
				}
			}
			return targetDir;
		}
		catch (IOException e) {
			throw new SourceReadException("ZIP konnte nicht entpackt werden: " + zipFile, e);
		}
	}

	/**
	 * Copies at most {@code limit} bytes and refuses anything longer.
	 *
	 * <p>Written out rather than using {@code transferTo} plus a check on
	 * {@link ZipEntry#getSize()}: that size comes from the archive's own header and is
	 * whatever the file claims. Only counting what actually arrives can bound the output.
	 *
	 * @return bytes written
	 */
	private static long copyBounded(InputStream in, OutputStream out, String entryName, long limit)
			throws IOException {
		byte[] buffer = new byte[8192];
		long written = 0;
		int read;

		while ((read = in.read(buffer)) != -1) {
			written += read;
			if (written > limit) {
				throw new SourceReadException("Der Eintrag '" + entryName
						+ "' ist entpackt größer als erlaubt — das ZIP wird nicht ausgepackt");
			}
			out.write(buffer, 0, read);
		}
		return written;
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
