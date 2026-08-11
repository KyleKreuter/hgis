package de.kreuter.hgis.export;

import de.kreuter.hgis.catalog.LayerField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides what each attribute is called in an exported file.
 *
 * <p>An export is read by a person and by QGIS, so the names have to be the ones the UI
 * shows -- {@code layer_field.source_name}, umlauts and spaces included -- not the
 * normalised column names the API uses internally. That is the rule; the rest of this
 * class exists because the rule cannot always be followed:
 *
 * <ol>
 * <li>Every feature carries its row id as {@code fid}, so an attribute of that name would
 *     produce a duplicate key -- one of the two would be lost, and which one is up to the
 *     reader.</li>
 * <li>Source names are not unique. DBF truncates field names to ten characters, so two
 *     genuinely different attributes can arrive under one name (same reason the feature
 *     API keys its properties by column name).</li>
 * </ol>
 *
 * <p>In both cases the field falls back to its column name, which {@code SqlIdentifier}
 * has already made unique within the layer and never lets be {@code fid}. Should even
 * that be taken -- one field's source name equal to another's column name -- a numeric
 * suffix is appended. The result is stable for a given layer: it depends only on the
 * fields and their order, never on which rows or which selection is exported.
 */
final class PropertyNaming {

	/** Every exported feature carries its row id under this key, before any attribute. */
	static final String FID_KEY = "fid";

	private PropertyNaming() {
	}

	/** Resolves the fields of a layer, in their catalog order, to unique property keys. */
	static List<ExportField> resolve(List<LayerField> fields) {
		Set<String> used = new LinkedHashSet<>();
		used.add(FID_KEY);

		List<ExportField> resolved = new ArrayList<>(fields.size());
		for (LayerField field : fields) {
			resolved.add(new ExportField(field.getColumnName(), key(field, used)));
		}
		return List.copyOf(resolved);
	}

	private static String key(LayerField field, Set<String> used) {
		String sourceName = field.getSourceName();
		String key = (sourceName == null || sourceName.isBlank()) ? field.getColumnName() : sourceName;

		if (used.contains(key)) {
			key = field.getColumnName();
		}
		for (int suffix = 2; used.contains(key); suffix++) {
			key = field.getColumnName() + "_" + suffix;
		}
		used.add(key);
		return key;
	}
}
