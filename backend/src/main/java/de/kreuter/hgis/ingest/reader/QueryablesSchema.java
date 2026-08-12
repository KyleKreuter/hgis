package de.kreuter.hgis.ingest.reader;

import java.sql.Date;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Turns one collection's {@code queryables} JSON Schema (OGC API Features, Part 3) into the
 * field list {@link OgcFeaturesSourceReader} needs to build a {@link
 * de.kreuter.hgis.ingest.spi.SourceSchema}, and the Geoportal dataset detail endpoint
 * (CONTRACT.md phase 23.4) needs to show a user before anything is imported. Both read the
 * same wire format and must resolve a field's display name identically -- a layer whose
 * columns came out named differently from what the dialog just showed would make the
 * preview a lie -- so the resolution lives here once instead of twice.
 *
 * <p>Public, unlike its siblings in this package: {@code geoportal} is the only caller
 * outside this package, for the dataset detail endpoint (CONTRACT.md 11.4), and it may
 * depend on {@code ingest.reader} -- that dependency never runs the other way.
 */
public final class QueryablesSchema {

	/** CONTRACT.md 11.4: capped at 20, enough to hint at the value range, not a full dump. */
	public static final int MAX_ENUM_VALUES = 20;

	/**
	 * @param technicalName the schema property name, what {@code fields} in CONTRACT.md
	 *                       11.6 carries and what a filter accepts
	 * @param title          the resolved display name, per CONTRACT.md 11.4's three-step rule
	 * @param javaType       target Java type, per {@link de.kreuter.hgis.common.TypeMapper}'s
	 *                       vocabulary
	 * @param idField        whether this property carries {@code x-ogc-role: id}
	 * @param enumValues     the schema's {@code enum} list, capped at {@link #MAX_ENUM_VALUES};
	 *                       empty, never null, when the field has none
	 */
	public record Field(String technicalName, String title, Class<?> javaType, boolean idField,
			List<String> enumValues) {
	}

	private QueryablesSchema() {
	}

	/**
	 * @param schema       the {@code queryables} document as a whole (its top-level
	 *                     {@code properties} object is read from it), in declaration order --
	 *                     which matched {@code x-ogc-propertySeq} order on every collection
	 *                     this was checked against live
	 * @param germanLabels the service directory's {@code gfiAttributes} for this collection,
	 *                     technical name to German label; empty when the directory carries
	 *                     none at all (plan section 3.5: a {@code showAll} entry)
	 */
	public static List<Field> parse(JsonNode schema, Map<String, String> germanLabels) {
		JsonNode properties = schema.path("properties");
		List<Field> fields = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : properties.properties()) {
			String technicalName = entry.getKey();
			JsonNode property = entry.getValue();
			String title = resolveTitle(technicalName, property, germanLabels);
			boolean idField = "id".equals(property.path("x-ogc-role").asString(null));
			fields.add(new Field(technicalName, title, javaType(property), idField, enumValues(property)));
		}
		return deduplicateTitles(fields);
	}

	/** CONTRACT.md 11.4's three-step rule: German label, then a distinct schema title, then the technical name. */
	private static String resolveTitle(String technicalName, JsonNode property, Map<String, String> germanLabels) {
		String german = germanLabels.get(technicalName);
		if (german != null && !german.isBlank()) {
			return german;
		}
		String schemaTitle = property.path("title").asString(null);
		if (schemaTitle != null && !schemaTitle.isBlank() && !schemaTitle.equals(technicalName)) {
			return schemaTitle;
		}
		return technicalName;
	}

	/**
	 * "On a collision the second field keeps its technical name as its title, or
	 * LayerFields.find becomes ambiguous" (CONTRACT.md 11.4). Only the second and later
	 * occurrence of a repeated title is rewritten; the first keeps whatever
	 * {@link #resolveTitle} gave it, matching the plan's own worked example.
	 */
	private static List<Field> deduplicateTitles(List<Field> fields) {
		Map<String, Boolean> seenTitles = new LinkedHashMap<>();
		List<Field> result = new ArrayList<>(fields.size());
		for (Field field : fields) {
			String key = field.title().toLowerCase(Locale.ROOT);
			if (seenTitles.putIfAbsent(key, Boolean.TRUE) != null) {
				result.add(new Field(field.technicalName(), field.technicalName(), field.javaType(),
						field.idField(), field.enumValues()));
			}
			else {
				result.add(field);
			}
		}
		return result;
	}

	/**
	 * Plan section 6.3, step 2. {@code integer} is always {@link Long}, never {@link
	 * Integer}: the JSON Schema names no upper bound, and a Hamburg feature id has already
	 * been observed past 100 million.
	 */
	private static Class<?> javaType(JsonNode property) {
		String type = property.path("type").asString("");
		String format = property.path("format").asString("");
		return switch (type) {
			case "integer" -> Long.class;
			case "number" -> Double.class;
			case "boolean" -> Boolean.class;
			case "string" -> switch (format) {
				case "date" -> Date.class;
				case "date-time" -> Instant.class;
				default -> String.class;
			};
			// array or object: stored as the value's own JSON text (plan 6.3, step 2) --
			// nothing observed live carried either, but queryables does not promise it never will.
			default -> String.class;
		};
	}

	private static List<String> enumValues(JsonNode property) {
		JsonNode enumNode = property.path("enum");
		if (!enumNode.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode value : enumNode) {
			if (values.size() >= MAX_ENUM_VALUES) {
				break;
			}
			values.add(value.asString());
		}
		return values;
	}
}
