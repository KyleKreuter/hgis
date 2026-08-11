package de.kreuter.hgis.ingest;

import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Envelope;

/**
 * Reads the first handful of features of a source to answer the two questions the schema
 * alone cannot: what the attribute values actually look like, and where on the planet the
 * data sits.
 *
 * <p>The values are what makes a wrong encoding visible -- {@code Müllerstraße} against
 * {@code MÃ¼llerstraÃŸe} against {@code M?llerstra?e} is a difference anybody can judge,
 * where the charset name alone is a guess the user has no way to check.
 *
 * <p>Strictly bounded: the inspection answers a dialog the user is waiting in front of,
 * and the file behind it may be half a gigabyte. Only {@link #FEATURE_LIMIT} features are
 * pulled from the (lazy) feature stream, which is why the bounding box is an approximation
 * of the beginning of the file rather than the true extent of the data.
 */
final class FeatureSample {

	/** Features read at most. Enough for a preview, few enough to stay instant on a big file. */
	static final int FEATURE_LIMIT = 100;

	/** Values kept per field, per the inspection contract. */
	static final int VALUES_PER_FIELD = 10;

	/**
	 * Cap for a single value. A text column can hold a whole document, and ten of those
	 * across a few dozen fields would make the preview larger than anything it describes.
	 */
	static final int MAX_VALUE_LENGTH = 200;

	private FeatureSample() {
	}

	/**
	 * @param valuesByField sample values in file order, keyed by {@link SourceField#name()};
	 *                      null entries are genuinely null values and stay distinguishable
	 *                      from empty text
	 * @param bbox          in the source CRS, empty when nothing usable was read
	 */
	record Result(Map<String, List<String>> valuesByField, Envelope bbox) {
	}

	static Result collect(SourceReader reader, List<SourceField> fields) {
		Map<String, List<String>> valuesByField = new LinkedHashMap<>();
		for (SourceField field : fields) {
			// ArrayList rather than List.of: null is a value here, not a missing one.
			valuesByField.put(field.name(), new ArrayList<>(VALUES_PER_FIELD));
		}
		Envelope bbox = new Envelope();

		try (Stream<SourceFeature> features = reader.features()) {
			Iterator<SourceFeature> iterator = features.limit(FEATURE_LIMIT).iterator();
			while (iterator.hasNext()) {
				SourceFeature feature = iterator.next();
				if (feature.geometry() != null && !feature.geometry().isEmpty()) {
					bbox.expandToInclude(feature.geometry().getEnvelopeInternal());
				}
				for (SourceField field : fields) {
					List<String> values = valuesByField.get(field.name());
					if (values.size() < VALUES_PER_FIELD) {
						values.add(toDisplayValue(feature.attributes().get(field.name())));
					}
				}
			}
		}
		return new Result(valuesByField, bbox);
	}

	/**
	 * One attribute value as the preview shows it. Everything becomes text, because the
	 * point is what the user will read in the table, not what type it was on the way --
	 * except null, which stays null so the frontend can render "no value" differently from
	 * an empty string.
	 */
	private static String toDisplayValue(Object value) {
		String text = switch (value) {
			case null -> null;
			case String s -> s;
			// Binary attributes have no readable form, and dumping their bytes would only
			// crowd out the fields that do.
			case byte[] bytes -> "[" + bytes.length + " Bytes]";
			default -> String.valueOf(value);
		};
		if (text == null || text.length() <= MAX_VALUE_LENGTH) {
			return text;
		}
		return text.substring(0, MAX_VALUE_LENGTH) + "…";
	}
}
