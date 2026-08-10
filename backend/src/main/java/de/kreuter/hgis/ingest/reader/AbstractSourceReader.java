package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceReader;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.util.factory.Hints;
import org.locationtech.jts.geom.Geometry;

/**
 * Shared bookkeeping for every {@link SourceReader} implementation in this package:
 * turning a GeoTools {@link SimpleFeature} into a {@link SourceFeature}, and counting the
 * ones that had to be dropped because their geometry was missing or unreadable.
 */
abstract class AbstractSourceReader implements SourceReader {

	static {
		// HgisBackendApplication forces longitude-first axis order for the whole JVM before
		// Spring touches GeoTools referencing. None of the readers in this package start
		// Spring (by design -- they are plain Java, testable without a backend), so the same
		// setting is mirrored here to keep standalone usage and tests consistent with
		// production. The value matches exactly; this does not override or race with the
		// application's own initializer, it just guarantees the same outcome either way.
		System.setProperty("org.geotools.referencing.forceXY", "true");
		Hints.putSystemDefault(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
	}

	private final AtomicLong skipped = new AtomicLong();

	protected final void recordSkip() {
		skipped.incrementAndGet();
	}

	@Override
	public final long skippedCount() {
		return skipped.get();
	}

	/** Wraps a plain iterator so {@link #skippedCount()} keeps working outside the readers here. */
	protected static <T> Stream<T> streamOf(Iterator<T> iterator) {
		Spliterator<T> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL);
		return StreamSupport.stream(spliterator, false);
	}

	/**
	 * Turns a stream of GeoTools features into a stream of {@link SourceFeature}, dropping
	 * (and counting) any feature whose geometry is missing, empty, or whose attributes
	 * cannot be read.
	 */
	protected final Stream<SourceFeature> toSourceFeatureStream(Iterator<SimpleFeature> iterator, List<SourceField> fields) {
		return streamOf(iterator)
				.map(feature -> toSourceFeature(feature, fields))
				.filter(Objects::nonNull);
	}

	private SourceFeature toSourceFeature(SimpleFeature feature, List<SourceField> fields) {
		try {
			if (!(feature.getDefaultGeometry() instanceof Geometry geometry) || geometry.isEmpty()) {
				recordSkip();
				return null;
			}
			Map<String, Object> attributes = new LinkedHashMap<>();
			for (SourceField field : fields) {
				attributes.put(field.name(), feature.getAttribute(field.name()));
			}
			return new SourceFeature(geometry, attributes);
		} catch (RuntimeException e) {
			recordSkip();
			return null;
		}
	}
}
