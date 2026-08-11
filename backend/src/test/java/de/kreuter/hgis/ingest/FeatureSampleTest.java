package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

/**
 * The value preview on its own, without a file or a database behind it.
 *
 * What matters here is not that values are copied out of a reader, but the two properties
 * the preview is useless without: a null that stays a null, and a file that is not read to
 * the end just because someone opened a dialog.
 */
class FeatureSampleTest {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

	@Test
	@DisplayName("liefert höchstens zehn Werte je Feld in Dateireihenfolge")
	void keepsTheFirstTenValuesPerField() {
		SourceReader reader = readerOf(25, index -> "Wert " + index);

		FeatureSample.Result result = FeatureSample.collect(reader, fields());

		assertThat(result.valuesByField().get("strasse"))
				.hasSize(FeatureSample.VALUES_PER_FIELD)
				.startsWith("Wert 0", "Wert 1")
				.endsWith("Wert 9");
	}

	@Test
	@DisplayName("null bleibt null und wird nicht zu einer leeren Zeichenkette")
	void keepsNullDistinguishableFromEmptyText() {
		SourceReader reader = readerOf(3, index -> switch (index) {
			case 0 -> "Müllerstraße";
			case 1 -> null;
			default -> "";
		});

		FeatureSample.Result result = FeatureSample.collect(reader, fields());

		assertThat(result.valuesByField().get("strasse")).containsExactly("Müllerstraße", null, "");
	}

	@Test
	@DisplayName("liest nur den Anfang der Datei, nicht alle Features")
	void readsOnlyTheBeginningOfTheFile() {
		AtomicInteger read = new AtomicInteger();
		SourceReader reader = new StubReader(Stream
				.iterate(0, index -> index + 1)
				.peek(index -> read.incrementAndGet())
				.map(index -> feature(index, "Wert " + index)));

		FeatureSample.Result result = FeatureSample.collect(reader, fields());

		assertThat(read.get())
				.as("an endless source must not be able to hang the inspection")
				.isEqualTo(FeatureSample.FEATURE_LIMIT);
		assertThat(result.bbox().getMaxX()).isEqualTo(FeatureSample.FEATURE_LIMIT - 1);
	}

	@Test
	@DisplayName("kappt übermäßig lange Werte sichtbar")
	void truncatesOverlongValues() {
		String overlong = "x".repeat(FeatureSample.MAX_VALUE_LENGTH + 50);
		SourceReader reader = readerOf(1, index -> overlong);

		FeatureSample.Result result = FeatureSample.collect(reader, fields());

		assertThat(result.valuesByField().get("strasse").get(0))
				.hasSize(FeatureSample.MAX_VALUE_LENGTH + 1)
				.endsWith("…");
	}

	@Test
	@DisplayName("spannt die Bbox über die gelesenen Geometrien")
	void spansTheBoundingBoxOverWhatWasRead() {
		SourceReader reader = readerOf(3, index -> "Wert " + index);

		FeatureSample.Result result = FeatureSample.collect(reader, fields());

		assertThat(result.bbox().getMinX()).isZero();
		assertThat(result.bbox().getMaxX()).isEqualTo(2);
	}

	// --- fixtures ------------------------------------------------------------------------

	private static List<SourceField> fields() {
		return List.of(new SourceField("strasse", String.class));
	}

	private static SourceReader readerOf(int count, java.util.function.IntFunction<String> valueAt) {
		return new StubReader(Stream.iterate(0, index -> index + 1)
				.limit(count)
				.map(index -> feature(index, valueAt.apply(index))));
	}

	private static SourceFeature feature(int index, String value) {
		Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(index, 50));
		// HashMap, not Map.of: a null value is exactly what this is about.
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("strasse", value);
		return new SourceFeature(point, attributes);
	}

	/** Minimal reader over a prepared stream; only {@link #features()} is ever called here. */
	private record StubReader(Stream<SourceFeature> stream) implements SourceReader {

		@Override
		public SourceSchema schema() {
			return new SourceSchema(GeometryType.MULTIPOINT, 4326, fields(),
					"UTF-8", SourceSchema.CrsConfidence.GUESSED, null);
		}

		@Override
		public Stream<SourceFeature> features() {
			return stream;
		}

		@Override
		public long skippedCount() {
			return 0;
		}

		@Override
		public void close() {
		}
	}
}
