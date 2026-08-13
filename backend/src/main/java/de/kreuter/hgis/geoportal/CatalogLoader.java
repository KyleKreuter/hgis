package de.kreuter.hgis.geoportal;

import com.opencsv.CSVReader;
import de.kreuter.hgis.common.AmbiguousTitles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fetches and merges the two files Hamburg publishes as its catalog (plan section 3.5) into
 * the entries {@link GeoportalCatalogService} holds: the 7.6&nbsp;MB service directory
 * ({@code services-internet.json}), which describes every OGC API Features collection there
 * is, and the dataset list ({@code datasets.csv}), which names the responsible agency, the
 * topic and the metadata record -- per dataset, which here means per service.
 *
 * <h2>The service directory is the spine (CONTRACT.md 11.9)</h2>
 *
 * <p>It used to be the other way round, and that cost 1164 of 1569 collections: a service
 * can carry more than one collection, the dataset list names only the service, and binding
 * each of its rows to the first collection found made every further collection of that
 * service unreachable. Nothing was missing from the files -- every entry of the service
 * directory names its own {@code url}, {@code collection}, title and {@code gfiAttributes}
 * -- so the catalog is now built from the directory, and the dataset list only adds agency,
 * topic and licence to the service its rows point at. Every collection of that service
 * inherits those; measured live, 403 of 405 services have such a row.
 *
 * <p>A dataset-list row the service directory knows nothing about still becomes a catalog
 * entry, just one CONTRACT.md 11.6 cannot import -- kind {@code WMS}, or {@code FEATURES} /
 * {@code BOTH} backed only by WFS, which stage 3 (plan section 8) reads, not this class.
 * Measured live: 108 of the 511 publicly reachable rows.
 *
 * <h2>Two shapes, decided by size</h2>
 *
 * <p>A service whose collections <em>are</em> the datasets is listed flat, one entry per
 * collection. A service whose collections are the object classes of one data model would
 * flood the list -- {@code xplan} alone has 247 -- so from {@link #SERVICE_ROW_THRESHOLD}
 * collections up the service is listed once and its collections are chosen in the detail
 * pane. Measured live: eight services cross that line, holding 475 collections between
 * them, and the listing ends at 1094 collections plus those eight rows.
 *
 * <h2>Titles</h2>
 *
 * <p>A collection is listed under its own name, not its service's. Where that name is
 * ambiguous -- 24 of them are, "2013" and "Verbindungsräume" among them, covering 66
 * entries -- the service name is prefixed, following {@link AmbiguousTitles}: the second
 * and every later occurrence is qualified, so a unique name is never dressed up.
 */
@Component
class CatalogLoader {

	private static final String SERVICE_DIRECTORY_URL = "https://geodienste.hamburg.de/services-internet.json";
	private static final String DATASET_LIST_URL = "https://geoportal-hamburg.de/urbandataplatform/datasets.csv";

	/** Plan section 3.5: the only value of {@code Aufrufbar} that means "reachable from the internet". */
	private static final String REACHABLE = "FHHNET/Internet";

	/**
	 * CONTRACT.md 11.9: from this many collections up, a service is listed as one row
	 * instead of as its collections. The line runs through the data rather than beside it --
	 * measured live, one service carries 19 collections and is listed flat, the next carries
	 * 20 and is not -- so this number is the contract's decision, not a fact about Hamburg,
	 * and moving it moves those two services.
	 */
	private static final int SERVICE_ROW_THRESHOLD = 20;

	/** Between the service name and the collection name when an ambiguous title has to be qualified. */
	private static final String TITLE_QUALIFIER_SEPARATOR = ": ";

	private static final Pattern MD_ID_PARAM = Pattern.compile("(?:docuuid|mdid)=([0-9A-Fa-f-]{36})");
	private static final Pattern TRAILING_PARENTHESIS = Pattern.compile("\\(([^()]+)\\)\\s*$");

	private final RestClient restClient;
	private final ObjectMapper mapper = new ObjectMapper();

	CatalogLoader(RestClient geoportalRestClient) {
		this.restClient = geoportalRestClient;
	}

	/** One collection of one service, as the service directory describes it. */
	private record OafCollection(String collection, String title, String datasetUri,
			Map<String, String> gfiAttributes) {
	}

	/**
	 * One OGC API Features service: its landing page, the name Hamburg's metadata record
	 * gives it, and every collection it carries, in the order the directory lists them.
	 */
	private record OafService(String apiUrl, String apiId, String name, List<OafCollection> collections) {
	}

	/**
	 * What the dataset list adds to a service: everything about it that is not per
	 * collection.
	 *
	 * @param wmsUrl the CSV's {@code WMS-Adresse} column, or null -- shared by every
	 *               collection of the service this row binds to, the same way {@code
	 *               hasWms} already is (plan "Kartenbilder aus dem Geoportal Hamburg",
	 *               stage 2): the row describes the service, not one collection of it
	 */
	private record DatasetRow(String agency, String attribution, String topic, String metadataUrl, boolean hasWms,
			String wmsUrl) {
	}

	/**
	 * The dataset list, split by whether the service directory knows the row's service:
	 * a known one only contributes {@link DatasetRow}, an unknown one is a catalog entry of
	 * its own.
	 */
	private record DatasetList(Map<String, DatasetRow> byLandingPage, List<GeoportalCatalogEntry> unboundDatasets) {
	}

	/**
	 * One row of the listing before its title is final, with the service name that qualifies
	 * it should the title turn out to be ambiguous. Null where nothing could qualify it: a
	 * service row (its title is that very name) and a dataset the service directory does not
	 * know (it belongs to no service here).
	 */
	private record Listing(GeoportalCatalogEntry entry, String qualifier) {
	}

	List<GeoportalCatalogEntry> load() {
		Map<String, OafService> services = loadServiceDirectory();
		DatasetList datasetList = loadDatasetList(services.keySet());
		return listing(services, datasetList);
	}

	// --- services-internet.json ---------------------------------------------------------

	private Map<String, OafService> loadServiceDirectory() {
		URI uri = URI.create(SERVICE_DIRECTORY_URL);
		return restClient.get().uri(uri).exchange((request, response) -> {
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new CatalogLoadException("Dienstverzeichnis antwortete mit " + response.getStatusCode());
			}
			Map<String, List<OafCollection>> collectionsByUrl = new LinkedHashMap<>();
			Map<String, String> serviceNameByUrl = new HashMap<>();
			try (InputStream body = response.getBody(); JsonParser parser = mapper.createParser(body)) {
				if (parser.nextToken() != JsonToken.START_ARRAY) {
					throw new CatalogLoadException("Dienstverzeichnis ist kein JSON-Array");
				}
				while (true) {
					JsonToken token = parser.nextToken();
					if (token == JsonToken.END_ARRAY || token == null) {
						break;
					}
					// One malformed entry among 6500 must not empty the whole catalog (plan
					// section 9, point 1) -- skip it and keep reading the rest of the array.
					try {
						addOafCollection(collectionsByUrl, serviceNameByUrl, parser.readValueAsTree());
					}
					catch (RuntimeException ignored) {
						// intentionally swallowed, see above
					}
				}
			}
			catch (IOException | JacksonException e) {
				throw new CatalogLoadException("Dienstverzeichnis kann nicht gelesen werden", e);
			}
			return toServices(collectionsByUrl, serviceNameByUrl);
		});
	}

	/**
	 * Every OAF entry of the directory is one collection of one service -- unlike before,
	 * none is dropped for belonging to a service already seen. The service's own name comes
	 * from its metadata record ({@code md_name}), which every one of the 1569 measured
	 * entries carries and which is the same string for every collection of one service; it
	 * is also, on all 403 services the dataset list covers, exactly that list's
	 * {@code Datensatzname}.
	 */
	private static void addOafCollection(Map<String, List<OafCollection>> collectionsByUrl,
			Map<String, String> serviceNameByUrl, JsonNode entry) {
		if (!"OAF".equals(entry.path("typ").asString(""))) {
			return;
		}
		String url = trimTrailingSlash(entry.path("url").asString(""));
		String collection = entry.path("collection").asString(null);
		if (url.isEmpty() || collection == null) {
			return;
		}
		JsonNode metadata = entry.path("datasets").isArray() && !entry.path("datasets").isEmpty()
				? entry.path("datasets").get(0)
				: null;
		String title = blankToNull(entry.path("name").asString(null));
		String datasetUri = metadata == null ? null : metadata.path("rs_id").asString(null);
		collectionsByUrl.computeIfAbsent(url, key -> new ArrayList<>())
				.add(new OafCollection(collection, title != null ? title : collection, datasetUri,
						readGfiAttributes(entry.path("gfiAttributes"))));

		String serviceName = metadata == null ? null : blankToNull(metadata.path("md_name").asString(null));
		if (serviceName != null) {
			serviceNameByUrl.putIfAbsent(url, serviceName);
		}
	}

	private static Map<String, OafService> toServices(Map<String, List<OafCollection>> collectionsByUrl,
			Map<String, String> serviceNameByUrl) {
		Map<String, OafService> services = new LinkedHashMap<>();
		for (Map.Entry<String, List<OafCollection>> entry : collectionsByUrl.entrySet()) {
			String url = entry.getKey();
			String apiId = apiIdOf(url);
			// Falls back to the API id rather than to a collection's title: a service listed
			// as one row would otherwise be named after whichever of its collections came
			// first, which is exactly the arbitrary binding this loader stopped making.
			String name = serviceNameByUrl.getOrDefault(url, apiId);
			services.put(url, new OafService(url, apiId, name, List.copyOf(entry.getValue())));
		}
		return services;
	}

	private static Map<String, String> readGfiAttributes(JsonNode node) {
		if (!node.isObject()) {
			return Map.of();
		}
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			if (entry.getValue().isString()) {
				result.put(entry.getKey(), entry.getValue().asString());
			}
		}
		return result;
	}

	private static String apiIdOf(String apiUrl) {
		int lastSlash = apiUrl.lastIndexOf('/');
		return lastSlash < 0 ? apiUrl : apiUrl.substring(lastSlash + 1);
	}

	// --- datasets.csv ---------------------------------------------------------------------

	private DatasetList loadDatasetList(Set<String> knownServiceUrls) {
		byte[] csv = fetchCsvBytes();
		Map<String, DatasetRow> byLandingPage = new LinkedHashMap<>();
		List<GeoportalCatalogEntry> unboundDatasets = new ArrayList<>();
		Set<String> takenIds = new HashSet<>();
		try (CSVReader csvReader =
				new CSVReader(new InputStreamReader(new ByteArrayInputStream(csv), StandardCharsets.UTF_8))) {
			String[] header = csvReader.readNextSilently();
			if (header == null) {
				throw new CatalogLoadException("Datensatzliste hat keine Kopfzeile");
			}
			if (header.length > 0) {
				header[0] = stripBom(header[0]);
			}
			Map<String, Integer> columnIndex = indexOf(header);

			String[] row;
			int rowNumber = 0;
			while ((row = csvReader.readNextSilently()) != null) {
				rowNumber++;
				try {
					readRow(row, columnIndex, rowNumber, knownServiceUrls, byLandingPage, unboundDatasets, takenIds);
				}
				catch (RuntimeException ignored) {
					// One malformed row must not empty the whole catalog (plan section 9, point 1).
				}
			}
		}
		catch (IOException e) {
			throw new CatalogLoadException("Datensatzliste kann nicht gelesen werden", e);
		}
		return new DatasetList(byLandingPage, unboundDatasets);
	}

	private byte[] fetchCsvBytes() {
		URI uri = URI.create(DATASET_LIST_URL);
		return restClient.get().uri(uri).exchange((request, response) -> {
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new CatalogLoadException("Datensatzliste antwortete mit " + response.getStatusCode());
			}
			try (InputStream body = response.getBody()) {
				return body.readAllBytes();
			}
		});
	}

	/**
	 * @param takenIds the ids already handed out to rows of this file, so no two rows can end
	 *                 up with the same one -- see {@link #fallbackId}
	 */
	private static void readRow(String[] row, Map<String, Integer> columnIndex, int rowNumber,
			Set<String> knownServiceUrls, Map<String, DatasetRow> byLandingPage,
			List<GeoportalCatalogEntry> unboundDatasets, Set<String> takenIds) {
		if (!REACHABLE.equals(valueOf(row, columnIndex, "Aufrufbar"))) {
			// The other two values (plan section 3.5) mean the service sits in Hamburg's
			// internal network -- nothing this backend, running outside it, could ever reach.
			return;
		}
		String title = valueOf(row, columnIndex, "Datensatzname");
		if (isBlank(title)) {
			return;
		}

		String wmsAddress = valueOf(row, columnIndex, "WMS-Adresse");
		String wfsAddress = valueOf(row, columnIndex, "WFS-Adresse");
		String oafLandingPage = valueOf(row, columnIndex, "OAF-Landing Page");
		boolean hasWms = !isBlank(wmsAddress);
		boolean hasWfs = !isBlank(wfsAddress);
		boolean hasOaf = !isBlank(oafLandingPage);
		if (!hasWms && !hasWfs && !hasOaf) {
			return; // no access path this catalog could ever offer
		}

		String organisation = valueOf(row, columnIndex, "Organisation");
		DatasetRow parsed = new DatasetRow(
				agencyOf(organisation),
				isBlank(organisation) ? null : organisation.trim(),
				blankToNull(valueOf(row, columnIndex, "Kategorie")),
				blankToNull(valueOf(row, columnIndex, "Metadaten")),
				hasWms,
				blankToNull(wmsAddress));

		String landingPage = hasOaf ? trimTrailingSlash(oafLandingPage.trim()) : "";
		if (knownServiceUrls.contains(landingPage)) {
			// The service directory describes this service collection by collection; this row
			// only says whose it is and under which licence -- see the class Javadoc.
			byLandingPage.put(landingPage, parsed);
			return;
		}

		// "kind" follows the plan's own glossary (section 2): FEATURES means an object
		// service exists (WFS and/or OGC API Features), independent of which -- stage 1
		// only reads OGC API Features, so a row that gets here is listed and not importable.
		String kind = (hasWfs || hasOaf) ? (hasWms ? "BOTH" : "FEATURES") : "WMS";
		unboundDatasets.add(new GeoportalCatalogEntry(
				"md:" + fallbackId(parsed.metadataUrl(), rowNumber, takenIds),
				title.trim(), kind, parsed.agency(), parsed.attribution(), parsed.topic(), parsed.metadataUrl(),
				null, null, null, Map.of(), parsed.wmsUrl()));
	}

	// --- merge ----------------------------------------------------------------------------

	/**
	 * The listing itself: every service either as its collections or as one row, then every
	 * dataset the service directory does not know. Order follows the service directory, and
	 * the dataset list after it -- which is also the order the title rule reads as "first"
	 * and "later".
	 */
	private static List<GeoportalCatalogEntry> listing(Map<String, OafService> services, DatasetList datasetList) {
		List<Listing> listings = new ArrayList<>();
		for (OafService service : services.values()) {
			DatasetRow row = datasetList.byLandingPage().get(service.apiUrl());
			if (service.collections().size() >= SERVICE_ROW_THRESHOLD) {
				listings.add(new Listing(serviceEntry(service, row), null));
			}
			else {
				for (OafCollection collection : service.collections()) {
					listings.add(new Listing(collectionEntry(service, collection, row), service.name()));
				}
			}
		}
		for (GeoportalCatalogEntry unbound : datasetList.unboundDatasets()) {
			listings.add(new Listing(unbound, null));
		}
		return qualifyAmbiguousTitles(listings);
	}

	/** One collection of a service, listed and importable as it stands. */
	private static GeoportalCatalogEntry collectionEntry(OafService service, OafCollection collection,
			DatasetRow row) {
		return new GeoportalCatalogEntry(
				service.apiId() + "/" + collection.collection(),
				collection.title(),
				kindOf(row),
				row == null ? null : row.agency(),
				row == null ? null : row.attribution(),
				row == null ? null : row.topic(),
				row == null ? null : row.metadataUrl(),
				collection.datasetUri(),
				service.apiUrl(),
				collection.collection(),
				collection.gfiAttributes(),
				row == null ? null : row.wmsUrl());
	}

	/**
	 * A service listed as one row (CONTRACT.md 11.9). It names no collection of its own, so
	 * it is not importable; its collections travel with it as complete entries, which is what
	 * lets a later detail or import call resolve the one the user picks without another fetch.
	 */
	private static GeoportalCatalogEntry serviceEntry(OafService service, DatasetRow row) {
		List<GeoportalCatalogEntry> collections = service.collections().stream()
				.map(collection -> collectionEntry(service, collection, row))
				.toList();
		return new GeoportalCatalogEntry(
				service.apiId(),
				service.name(),
				kindOf(row),
				row == null ? null : row.agency(),
				row == null ? null : row.attribution(),
				row == null ? null : row.topic(),
				row == null ? null : row.metadataUrl(),
				// One metadata record per service, measured: no service of the directory
				// names more than one, so the first collection's is the service's own.
				collections.isEmpty() ? null : collections.get(0).datasetUri(),
				service.apiUrl(),
				null,
				Map.of(),
				row == null ? null : row.wmsUrl(),
				collections);
	}

	/**
	 * Every entry built from the service directory has an object service by construction;
	 * whether a map image exists next to it is the one thing only the dataset list says.
	 */
	private static String kindOf(DatasetRow row) {
		return row != null && row.hasWms() ? "BOTH" : "FEATURES";
	}

	/**
	 * CONTRACT.md 11.9: an ambiguous title is qualified with its service name, at the second
	 * and every later occurrence. A repeat with nothing to qualify it -- a service row, or a
	 * dataset the service directory does not know -- keeps its title; measured live, neither
	 * ever repeats.
	 */
	private static List<GeoportalCatalogEntry> qualifyAmbiguousTitles(List<Listing> listings) {
		boolean[] repeats = AmbiguousTitles.repeats(listings.stream().map(listing -> listing.entry().title()).toList());
		List<GeoportalCatalogEntry> entries = new ArrayList<>(listings.size());
		for (int i = 0; i < listings.size(); i++) {
			Listing listing = listings.get(i);
			entries.add(repeats[i] && listing.qualifier() != null
					? listing.entry().withTitle(listing.qualifier() + TITLE_QUALIFIER_SEPARATOR + listing.entry().title())
					: listing.entry());
		}
		return entries;
	}

	/**
	 * Short form for the catalog list row (CONTRACT.md 11.2's {@code "agency": "BUKEA"}):
	 * the parenthesised abbreviation the CSV's {@code Organisation} column carries for most
	 * agencies, e.g. {@code "Behörde für Umwelt, Klima, Energie und Agrarwirtschaft (BUKEA)"}
	 * -> {@code "BUKEA"}. An organisation without one (measured live: public corporations
	 * such as Hamburg Port Authority, and a handful of Behörden) keeps its full name here --
	 * there is no reliable way to abbreviate a name Hamburg itself does not abbreviate.
	 */
	private static String agencyOf(String organisation) {
		if (isBlank(organisation)) {
			return null;
		}
		String trimmed = organisation.trim();
		Matcher matcher = TRAILING_PARENTHESIS.matcher(trimmed);
		return matcher.find() ? matcher.group(1).trim() : trimmed;
	}

	/**
	 * The metadata record's own uuid, when a URL in this row's own columns carries one; a row
	 * number otherwise, so every entry still gets a stable, unique id within one load.
	 *
	 * <p>The uuid is not unique by itself, which was measured and cost two datasets: two
	 * pairs of rows -- {@code ALKIS - Flurstücke und Gebäude (gelb)} with {@code
	 * Gewerbeflächen-Exposé}, and the two {@code Zentraler AdressService Hamburg} rows --
	 * point at one metadata record each. Both second rows were listed under an id that
	 * already belonged to the first, so asking for their detail answered with the other
	 * dataset. A repeat therefore falls back to the row number, exactly as a row with no
	 * uuid at all does; the first row of a pair keeps the id it always had.
	 */
	private static String fallbackId(String metadataUrl, int rowNumber, Set<String> takenIds) {
		String id = "row-" + rowNumber;
		if (metadataUrl != null) {
			Matcher matcher = MD_ID_PARAM.matcher(metadataUrl);
			if (matcher.find()) {
				String fromMetadataRecord = matcher.group(1).toLowerCase(Locale.ROOT);
				id = takenIds.contains(fromMetadataRecord) ? id : fromMetadataRecord;
			}
		}
		takenIds.add(id);
		return id;
	}

	private static Map<String, Integer> indexOf(String[] header) {
		Map<String, Integer> index = new LinkedHashMap<>();
		for (int i = 0; i < header.length; i++) {
			index.put(header[i], i);
		}
		return index;
	}

	private static String valueOf(String[] row, Map<String, Integer> columnIndex, String columnName) {
		Integer i = columnIndex.get(columnName);
		return i == null || i >= row.length ? null : row[i];
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String blankToNull(String value) {
		return isBlank(value) ? null : value.trim();
	}

	private static String trimTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	/** The CSV is served with a UTF-8 BOM (measured live), which a plain UTF-8 reader turns
	 *  into a literal U+FEFF glued to the first header cell instead of stripping it. */
	private static String stripBom(String firstHeaderCell) {
		return firstHeaderCell.startsWith("\uFEFF") ? firstHeaderCell.substring(1) : firstHeaderCell;
	}
}
