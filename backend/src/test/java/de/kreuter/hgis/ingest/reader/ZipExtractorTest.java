package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
	@DisplayName("a normal shapefile set extracts, and the directory is handed to the caller")
	void extractsAnOrdinaryArchive(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("normal.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (String name : new String[] { "gebaeude.shp", "gebaeude.dbf", "gebaeude.shx" }) {
				out.putNextEntry(new ZipEntry(name));
				out.write("Inhalt".getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}

		Set<Path> before = hgisTempDirs();
		Path extracted = ZipExtractor.extract(zip);
		try {
			assertThat(extracted.resolve("gebaeude.shp")).exists();
			assertThat(extracted.resolve("gebaeude.dbf")).exists();
			// On success, ownership of the directory passes to the caller -- it must still
			// be there, not swept up by the same cleanup that protects the failure paths.
			assertThat(hgisTempDirs())
					.as("extract() must not delete the directory it just handed back")
					.contains(extracted)
					.containsAll(before);
		}
		finally {
			FileTree.deleteQuietly(extracted);
		}
	}

	@Test
	@DisplayName("an entry escaping the target directory is refused (Zip Slip), no temp directory is left behind")
	void refusesPathTraversal(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("slip.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			out.putNextEntry(new ZipEntry("../../etc/passwd"));
			out.write("kaputt".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}

		Set<Path> before = hgisTempDirs();

		assertThatThrownBy(() -> ZipExtractor.extract(zip))
				.isInstanceOf(SourceReadException.class)
				.hasMessageContaining("Unsicherer Pfad");

		assertThat(hgisTempDirs()).as("no temp directory left behind after a failed extract()").isEqualTo(before);
	}

	@Test
	@DisplayName("an archive with absurdly many entries is refused, no temp directory is left behind")
	void refusesTooManyEntries(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("many.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (int i = 0; i < 200; i++) {
				out.putNextEntry(new ZipEntry("datei" + i + ".txt"));
				out.write(new byte[] { 1 });
				out.closeEntry();
			}
		}

		Set<Path> before = hgisTempDirs();

		assertThatThrownBy(() -> ZipExtractor.extract(zip))
				.isInstanceOf(SourceReadException.class)
				.hasMessageContaining("Einträge");

		assertThat(hgisTempDirs()).as("no temp directory left behind after a failed extract()").isEqualTo(before);
	}

	@Test
	@DisplayName("a compression bomb is stopped while unpacking, no partially written temp directory is left behind")
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

		Set<Path> before = hgisTempDirs();

		assertThatThrownBy(() -> ZipExtractor.extract(zip))
				.isInstanceOf(SourceReadException.class)
				.hasMessageContaining("größer als erlaubt");

		// The interesting case: by the time the bound trips, part of "gross.bin" is already
		// on disk. Cleanup has to remove that partial file along with the directory, not
		// just the directory itself.
		assertThat(hgisTempDirs()).as("no temp directory left behind after a failed extract()").isEqualTo(before);
	}

	@Test
	@DisplayName("a missing ZIP file is refused, no temp directory is left behind")
	void refusesAMissingZipFile(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("does-not-exist.zip");

		Set<Path> before = hgisTempDirs();

		assertThatThrownBy(() -> ZipExtractor.extract(zip)).isInstanceOf(SourceReadException.class);

		assertThat(hgisTempDirs()).as("no temp directory left behind after a failed extract()").isEqualTo(before);
	}

	@Test
	@DisplayName("a corrupt ZIP file is refused, no temp directory is left behind")
	void refusesACorruptZipFile(@TempDir Path dir) throws IOException {
		Path zip = dir.resolve("kaputt.zip");
		Files.write(zip, "das ist kein ZIP".getBytes(StandardCharsets.UTF_8));

		Set<Path> before = hgisTempDirs();

		assertThatThrownBy(() -> ZipExtractor.extract(zip)).isInstanceOf(SourceReadException.class);

		assertThat(hgisTempDirs()).as("no temp directory left behind after a failed extract()").isEqualTo(before);
	}

	private static void writeZeros(OutputStream out, long total) throws IOException {
		byte[] block = new byte[1024 * 1024];
		for (long written = 0; written < total; written += block.length) {
			out.write(block);
		}
	}

	/**
	 * Snapshots the {@code hgis-shp-*} siblings directly inside the real system temp
	 * directory, taken before and after a call to {@link ZipExtractor#extract(Path)}.
	 *
	 * <p>The obvious way to isolate this -- point {@code java.io.tmpdir} at a {@code @TempDir}
	 * for the duration of the test -- does not work here: the JDK resolves and caches that
	 * property once per JVM the first time any code creates a temp file or directory, and
	 * JUnit's own {@code @TempDir} extension has already done that before this test class
	 * ever runs. Reassigning the system property afterwards has no effect on subsequent
	 * {@link Files#createTempDirectory}. Watching the real system temp directory instead
	 * needs no such redirect and works regardless of test order, since Surefire and JUnit run
	 * these methods on a single thread -- there is never another test creating or deleting a
	 * {@code hgis-shp-*} directory while one of these snapshots is being taken.
	 */
	private static Set<Path> hgisTempDirs() throws IOException {
		Path systemTmp = Path.of(System.getProperty("java.io.tmpdir"));
		try (Stream<Path> listing = Files.list(systemTmp)) {
			return listing.filter(p -> p.getFileName().toString().startsWith("hgis-shp-"))
					.collect(Collectors.toSet());
		}
	}
}
