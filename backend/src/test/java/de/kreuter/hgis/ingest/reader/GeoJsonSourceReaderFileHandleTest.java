package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.UnixOperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rejected GeoJSON file must not cost a file handle. The reader locates the {@code
 * features} array with a parser it opens itself and hands to the caller only once that
 * succeeded -- so every rejection in between is a handle nothing holds a reference to any
 * more, and nothing will ever close.
 *
 * <p>The trigger is ordinary: a single GeoJSON {@code Feature} uploaded where a {@code
 * FeatureCollection} was expected. The upload deliberately stays on disk so the user can
 * retry, which makes the leak accumulate one handle per attempt -- and both the inspect
 * step and the import itself open the file, so a user working through what is wrong with
 * their file pays twice per try.
 *
 * <p>Counted with the JVM's own open-descriptor count, which is only available on Unix;
 * elsewhere the test skips rather than pretending to check something.
 */
class GeoJsonSourceReaderFileHandleTest {

	/** Far more than the tolerance below, so a leak cannot hide inside the JVM's own churn. */
	private static final int ATTEMPTS = 400;

	/** Room for descriptors other parts of the JVM open while this runs. */
	private static final long TOLERATED_GROWTH = 40;

	@Test
	@DisplayName("ein einzelnes Feature statt einer FeatureCollection lässt keinen Dateizeiger offen")
	void aBareFeatureLeavesNoOpenFileHandle(@TempDir Path dir) throws IOException {
		UnixOperatingSystemMXBean os = unixOperatingSystem();
		Path file = dir.resolve("einzelnes-feature.geojson");
		Files.writeString(file, """
				{"type":"Feature","geometry":{"type":"Point","coordinates":[10.0,53.5]},"properties":{"name":"Hamburg"}}
				""");

		// One rejection first: whatever the JVM opens lazily on this path is then already open
		// and cannot be mistaken for the growth measured below.
		assertThatThrownBy(() -> new GeoJsonSourceReader(file, null)).isInstanceOf(SourceReadException.class);

		long before = os.getOpenFileDescriptorCount();
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			assertThatThrownBy(() -> new GeoJsonSourceReader(file, null))
					.isInstanceOf(SourceReadException.class)
					.hasMessageContaining("features");
		}

		assertThat(os.getOpenFileDescriptorCount() - before)
				.as("%d abgewiesene Dateien dürfen keine %d offenen Dateizeiger hinterlassen", ATTEMPTS, ATTEMPTS)
				.isLessThan(TOLERATED_GROWTH);
	}

	private static UnixOperatingSystemMXBean unixOperatingSystem() {
		OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
		assumeTrue(os instanceof UnixOperatingSystemMXBean,
				"Offene Dateizeiger lassen sich nur unter Unix zählen");
		return (UnixOperatingSystemMXBean) os;
	}
}
