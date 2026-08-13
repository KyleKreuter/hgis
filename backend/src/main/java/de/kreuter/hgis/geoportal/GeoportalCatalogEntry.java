package de.kreuter.hgis.geoportal;

import java.util.List;
import java.util.Map;

/**
 * One entry of the held-in-memory catalog (CONTRACT.md 11.2), built by {@link
 * CatalogLoader} from Hamburg's service directory and dataset list. Carries everything the
 * listing needs plus everything a later detail or import call needs, so those two never
 * have to re-fetch either upstream file -- only the OGC API Features service itself.
 *
 * <p>An entry is one of three things (CONTRACT.md 11.9):
 *
 * <ul>
 * <li>a <em>collection</em> -- the usual case, 1094 of them measured: one collection of one
 * service, listed on its own and importable as it stands;</li>
 * <li>a <em>service</em> -- a service carrying 20 collections or more, listed as a single
 * row whose {@link #collections()} are chosen in the detail pane; it names no collection of
 * its own and is therefore not importable, only browsable;</li>
 * <li>a <em>dataset without an OGC API Features binding</em> -- a row of the dataset list
 * the service directory knows nothing about, listed so the catalog still shows it exists,
 * importable in no stage this backend has reached.</li>
 * </ul>
 *
 * @param id            opaque, built here, never parsed by a client: {@code apiId/collection}
 *                      for a collection, {@code apiId} for a service listed as one row,
 *                      {@code md:<uuid>} for a dataset with no OGC API Features binding
 * @param title         the collection's own name (CONTRACT.md 11.9), the service's name for
 *                      a service listed as one row, the dataset list's {@code Datensatzname}
 *                      for a row the service directory does not know; qualified with the
 *                      service name where it would otherwise be ambiguous -- see
 *                      {@link CatalogLoader}
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
 * @param datasetUri    the service directory's {@code rs_id} for this collection's metadata
 *                      record, a {@code registry.gdi-de.org} URI; null when the service
 *                      directory names none
 * @param apiUrl        the OGC API Features landing page, e.g.
 *                      {@code https://api.hamburg.de/datasets/v1/strassenbaumkataster};
 *                      null only for a dataset with no OGC API Features binding at all
 * @param collection    the collection id within {@code apiUrl}; null for a service listed as
 *                      one row -- none of its collections is chosen yet -- and for a dataset
 *                      with no binding
 * @param gfiAttributes technical field name to German label, from the service directory's
 *                      {@code gfiAttributes}; empty, never null, when the directory carries
 *                      none for this collection (plan section 3.5: a {@code showAll} entry),
 *                      for a service listed as one row (its labels are per collection) and
 *                      for a dataset with no binding
 * @param wmsUrl        the CSV's {@code WMS-Adresse} column (plan "Kartenbilder aus dem
 *                      Geoportal Hamburg", stage 2), or null when the dataset names no map
 *                      image service; lets the map-image dialog fetch this dataset's WMS
 *                      capabilities for a {@code kind} of {@code WMS} or {@code BOTH}
 *                      without the client having to already know the address
 * @param collections   this service's collections, each a complete entry of its own, held
 *                      for the detail pane and for the lookup a later import does by the
 *                      chosen collection's id; empty, never null, for everything but a
 *                      service listed as one row
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
		Map<String, String> gfiAttributes,
		String wmsUrl,
		List<GeoportalCatalogEntry> collections) {

	GeoportalCatalogEntry {
		collections = List.copyOf(collections);
	}

	/**
	 * An entry that stands for one collection, or for none at all -- everything except a
	 * service listed as one row. Spares every such call site, and every test fixture, a
	 * trailing {@code List.of()} that says nothing.
	 */
	GeoportalCatalogEntry(String id, String title, String kind, String agency, String attribution, String topic,
			String metadataUrl, String datasetUri, String apiUrl, String collection,
			Map<String, String> gfiAttributes, String wmsUrl) {
		this(id, title, kind, agency, attribution, topic, metadataUrl, datasetUri, apiUrl, collection,
				gfiAttributes, wmsUrl, List.of());
	}

	boolean hasOgcFeatures() {
		return apiUrl != null && collection != null;
	}

	/** Whether this is a service listed as one row, whose collections are chosen in the detail pane. */
	boolean isService() {
		return !collections.isEmpty();
	}

	/**
	 * CONTRACT.md 11.9: {@code 1} for a row that is one collection -- the overwhelming
	 * majority -- and the real count for a service listed as one row. {@code > 1} is exactly
	 * the client's signal "pick a collection before importing".
	 */
	int collectionCount() {
		return collections.isEmpty() ? 1 : collections.size();
	}

	GeoportalCatalogEntry withTitle(String newTitle) {
		return new GeoportalCatalogEntry(id, newTitle, kind, agency, attribution, topic, metadataUrl, datasetUri,
				apiUrl, collection, gfiAttributes, wmsUrl, collections);
	}
}
