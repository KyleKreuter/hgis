package de.kreuter.hgis.features;

import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFields;
import java.util.ArrayList;
import java.util.List;

/**
 * The fields a feature query may name: the layer's own, plus {@code fid}.
 *
 * <p>{@code fid} is not a {@code layer_field} row. It is the primary key every layer table
 * is created with, so it has no display name and no ordinal, and nothing in the catalog
 * describes it. It is still the one column a client needs most: a program that reads a
 * selection, computes something from it and asks for the same objects again has nothing
 * else to name them by. Sorting has accepted it from the start; filtering did not, and
 * {@code fid IN (…)} was the missing half of that round trip.
 *
 * <p>Handing it out as a synthetic {@link LayerField} rather than special-casing it in the
 * parser is what keeps it honest: it goes through the same name resolution as every other
 * field, so a layer that happens to carry a field displayed as "FID" -- an ESRI shapefile
 * brings one -- makes the name ambiguous and is rejected, instead of the row id quietly
 * shadowing the imported attribute. It also gets the type rules for free: {@code bigint},
 * so {@code fid LIKE '1%'} is refused with the same message any other number column gives.
 */
final class QueryFields {

	/**
	 * The row id as a field. {@code bigint} because that is what the table declares, and
	 * ordinal -1 because it precedes every field a file brought with it.
	 *
	 * <p>Shared and immutable: {@link LayerField} exposes no setter but {@code rename},
	 * which nothing here calls.
	 */
	private static final LayerField ROW_ID = new LayerField(null, "fid", "fid", "bigint", -1);

	private QueryFields() {
	}

	/** Whether a resolved field is the row id rather than one of the layer's own. */
	static boolean isRowId(LayerField field) {
		return field == ROW_ID;
	}

	/** The layer's fields with the row id in front, as a filter may name them. */
	static List<LayerField> withRowId(List<LayerField> fields) {
		List<LayerField> all = new ArrayList<>(fields.size() + 1);
		all.add(ROW_ID);
		all.addAll(fields);
		return all;
	}

	/**
	 * The field to sort by, or {@code null} for the row id.
	 *
	 * <p>Null is not "unsorted": it means the ordering is the fid alone, which
	 * {@code FeatureQueryService.orderBy} and the keyset condition both handle as their
	 * simple case, and which the cursor then carries without a sort value. Returning the
	 * synthetic field instead would work but would change what a cursor contains, for no
	 * gain.
	 *
	 * @param sort the sort parameter; blank or absent is the same as {@code fid}
	 */
	static LayerField requireSortField(String sort, List<LayerField> fields) {
		if (sort == null || sort.isBlank()) {
			return null;
		}
		LayerField resolved = LayerFields.require(sort, withRowId(fields), "Sortierfeld");
		return isRowId(resolved) ? null : resolved;
	}
}
