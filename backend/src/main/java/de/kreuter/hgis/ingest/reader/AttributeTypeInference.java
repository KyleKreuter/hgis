package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.ingest.spi.SourceField;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Widens a stream of observed values per attribute key into one Java type per key, for
 * formats that do not declare a schema up front (GeoJSON properties, CSV columns).
 *
 * Nulls are ignored for typing but still register the key -- a field that happens to be
 * empty in the first sampled row must not force every later value into a String column,
 * and a column must still show up even if every sampled row left it blank.
 */
final class AttributeTypeInference {

	private final Set<String> order = new LinkedHashSet<>();
	private final Map<String, Class<?>> types = new LinkedHashMap<>();

	void observe(String key, Object value) {
		order.add(key);
		if (value != null) {
			types.merge(key, value.getClass(), AttributeTypeInference::widen);
		}
	}

	List<SourceField> fields() {
		return order.stream()
				.map(key -> new SourceField(key, types.getOrDefault(key, String.class)))
				.toList();
	}

	private static Class<?> widen(Class<?> a, Class<?> b) {
		if (a.equals(b)) {
			return a;
		}
		if (isNumeric(a) && isNumeric(b)) {
			return Double.class;
		}
		return String.class;
	}

	private static boolean isNumeric(Class<?> type) {
		return type == Long.class || type == Double.class;
	}
}
