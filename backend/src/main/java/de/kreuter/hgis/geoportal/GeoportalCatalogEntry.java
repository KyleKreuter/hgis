package de.kreuter.hgis.geoportal;

import java.util.Map;

/**
 * One entry of the held-in-memory catalog (CONTRACT.md 11.2), built by {@link
 * CatalogLoader} from Hamburg's service directory and dataset list. Carries everything the
 * listing needs plus everything a later detail or import call needs, so those two never
 * have to re-fetch either upstream file -- only the OGC API Features service itself.
 *
 * @param id            opaque, built here, never parsed by a client: {@code apiId/collection}
 *                      when an OGC API Features binding exists, {@code md:<uuid>} otherwise
 * @param title         the dataset's name (CSV column {@code Datensatzname})
 * @param kind          {@code FEATURES}, {@code WMS} or {@code BOTH} -- see {@link
 *                      CatalogLoader} for how the two source files decide it
 * @param agency        short form of the responsible body, for the list row; the
 *                      parenthesised abbreviation in {@code Organisation} when the CSV
 *                      carries one, the whole string otherwise
 * @param attribution   the CSV's {@code Organisation} column, unabridged -- what CONTRACT.md
 *                      11.4 returns as {@code attribution} and what phase 23.7 stores on the
 *                      layer; see {@link CatalogLoader} for why this is not "Freie und
 *                      Hansestadt Hamburg, " plus the agency name
 * @param topic         the CSV's {@code Kategorie} column, one string, not split further
 * @param metadataUrl   the CSV's {@code Metadaten} column: a {@code metaver.de} page,
 *                      already in the exact shape CONTRACT.md 11.4 expects
 * @param datasetUri    the service directory's {@code rs_id} for this dataset's metadata
 *                      record, a {@code registry.gdi-de.org} URI; null when no entry of the
 *                      service directory could be matched to this row at all
 * @param apiUrl        the OGC API Features landing page, e.g.
 *                      {@code https://api.hamburg.de/datasets/v1/strassenbaumkataster};
 *                      null when the dataset has no OGC API Features access at all
 * @param collection    the collection id within {@code apiUrl}; null under the same
 *                      condition as {@code apiUrl}
 * @param gfiAttributes technical field name to German label, from the service directory's
 *                      {@code gfiAttributes}; empty, never null, when the directory carries
 *                      none for this entry (plan section 3.5: a {@code showAll} entry) or
 *                      when there is no OGC API Features binding to read it from at all
 */
record GeoportalCatalogEntry(
		String id,
		String title,
		String kind,
		String agency,
		String attribution,
		String topic,
		String metadataUrl,
		String datasetUri,
		String apiUrl,
		String collection,
		Map<String, String> gfiAttributes) {

	boolean hasOgcFeatures() {
		return apiUrl != null && collection != null;
	}
}
