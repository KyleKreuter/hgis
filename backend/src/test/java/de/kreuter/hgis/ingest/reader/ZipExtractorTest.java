package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bounds on unpacking (plan section A.9).
 *
 * An upload limit does not bound what an archive expands to: a few hundred kilobytes of
 * zeros become gigabytes, and the only thing standing between that and a full disk is
 * this class.
 */
class ZipExtractorTest {

	@Test
	@DisplayName("a normal shapefile set extracts")
	void extractsAnOrdinaryArchive(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("normal.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (String name : new String[] { "gebaeude.shp", "gebaeude.dbf", "gebaeude.shx" }) {
				out.putNextEntry(new ZipEntry(name));
				out.write("Inhalt".getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}

		Path extracted = ZipExtractor.extract(zip);

		assertThat(extracted.resolve("gebaeude.shp")).exists();
		assertThat(extracted.resolve("gebaeude.dbf")).exists();
	}

	@Test
	@DisplayName("an entry escaping the target directory is refused (Zip Slip)")
	void refusesPathTraversal(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("slip.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			out.putNextEntry(new ZipEntry("../../etc/passwd"));
			out.write("kaputt".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}

		assertThatThrownBy(() -> ZipExtractor.extract(zip))
				.isInstanceOf(SourceReadException.class)
				.hasMessageContaining("Unsicherer Pfad");
	}

	@Test
	@DisplayName("an archive with absurdly many entries is refused")
	void refusesTooManyEntries(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("many.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (int i = 0; i < 200; i++) {
				out.putNextEntry(new ZipEntry("datei" + i + ".txt"));
				out.write(new byte[] { 1 });
				out.closeEntry();
			}
		}

		assertThatThrownBy(() -> ZipExtractor.extract(zip))
				.isInstanceOf(SourceReadException.class)
				.hasMessageContaining("Einträge");
	}

	@Test
	@DisplayName("a compression bomb is stopped while unpacking, not by its declared size")
	void refusesAnEntryThatExpandsBeyondTheLimit(@TempDir Path dir) throws IOException {
		// Zeros compress to almost nothing, which is exactly what makes this dangerous:
		// the archive passes any upload limit and only reveals itself while being written.
		Path zip = dir.resolve("bombe.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			out.putNextEntry(new ZipEntry("gross.bin"));
			// Just past the per-entry ceiling. Writing several gigabytes would prove the
			// same thing and cost the test suite ten seconds every run.
			writeZeros(out, 1100L * 1024 * 1024);
			out.closeEntry();
		}

		assertThatThrownBy(() -> ZipExtractor.extract(zip))
				.isInstanceOf(SourceReadException.class)
				.hasMessageContaining("größer als erlaubt");
	}

	private static void writeZeros(OutputStream out, long total) throws IOException {
		byte[] block = new byte[1024 * 1024];
		for (long written = 0; written < total; written += block.length) {
			out.write(block);
		}
	}
}
