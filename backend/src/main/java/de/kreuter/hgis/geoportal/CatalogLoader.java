package de.kreuter.hgis.geoportal;

import com.opencsv.CSVReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * ({@code services-internet.json}), which knows every OGC API Features binding but nothing
 * about public reachability, and the dataset list ({@code datasets.csv}), which is the
 * other way round -- one row per dataset, reachability included, but no collection id.
 *
 * <p>The dataset list is the join's spine: CONTRACT.md 11.2's 511 entries are exactly its
 * rows whose {@code Aufrufbar} column reads {@code FHHNET/Internet} (plan section 3.5),
 * verified against the live files while this was written. The service directory only ever
 * <em>adds</em> the OGC API Features binding (and its German field labels) to a row that
 * already exists; a row the service directory cannot match still becomes a catalog entry,
 * just one CONTRACT.md 11.6 cannot import yet -- kind {@code WMS}, or {@code FEATURES} /
 * {@code BOTH} backed only by WFS, which stage 3 (plan section 8) reads, not this class.
 *
 * <h2>A genuine simplification, not a silent one</h2>
 *
 * <p>One API landing page can hold more than one collection -- measured live, up to 247 for
 * a single dataset row -- and the dataset list names only the landing page, never which
 * collection is "the" one. Splitting every such row into one catalog entry per collection
 * would make the catalog's size depend on an implementation detail the two source files
 * disagree about, and CONTRACT.md's own worked example counts by dataset, not by
 * collection. This loader instead keeps one entry per dataset row and binds it to the
 * <em>first</em> collection the service directory lists for that landing page --
 * deterministic, but arbitrary beyond that. It is the one respect in which "importable via
 * CONTRACT.md 11.6" does not yet mean "every collection Hamburg offers under this dataset
 * name is reachable"; see the phase 23 backend report for the full account.
 */
@Component
class CatalogLoader {

	private static final String SERVICE_DIRECTORY_URL = "https://geodienste.hamburg.de/services-internet.json";
	private static final String DATASET_LIST_URL = "https://geoportal-hamburg.de/urbandataplatform/datasets.csv";

	/** Plan section 3.5: the only value of {@code Aufrufbar} that means "reachable from the internet". */
	private static final String REACHABLE = "FHHNET/Internet";

	private static final Pattern MD_ID_PARAM = Pattern.compile("(?:docuuid|mdid)=([0-9A-Fa-f-]{36})");
	private static final Pattern TRAILING_PARENTHESIS = Pattern.compile("\\(([^()]+)\\)\\s*$");

	private final RestClient restClient;
	private final ObjectMapper mapper = new ObjectMapper();

	CatalogLoader(RestClient geoportalRestClient) {
		this.restClient = geoportalRestClient;
	}

	/**
	 * One OGC API Features binding as the service directory describes it, keyed by its
	 * landing page URL so a dataset row's {@code OAF-Landing Page} column resolves it
	 * directly.
	 */
	private record OafBinding(String apiUrl, String apiId, String collection, String rsId,
			Map<String, String> gfiAttributes) {
	}

	List<GeoportalCatalogEntry> load() {
		Map<String, OafBinding> oafByApiUrl = loadServiceDirectory();
		return loadDatasetList(oafByApiUrl);
	}

	// --- services-internet.json ---------------------------------------------------------

	private Map<String, OafBinding> loadServiceDirectory() {
		URI uri = URI.create(SERVICE_DIRECTORY_URL);
		return restClient.get().uri(uri).exchange((request, response) -> {
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new CatalogLoadException("Dienstverzeichnis antwortete mit " + response.getStatusCode());
			}
			Map<String, OafBinding> byUrl = new LinkedHashMap<>();
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
						addOafBinding(byUrl, parser.readValueAsTree());
					}
					catch (RuntimeException ignored) {
						// intentionally swallowed, see above
					}
				}
			}
			catch (IOException | JacksonException e) {
				throw new CatalogLoadException("Dienstverzeichnis kann nicht gelesen werden", e);
			}
			return byUrl;
		});
	}

	private static void addOafBinding(Map<String, OafBinding> byUrl, JsonNode entry) {
		if (!"OAF".equals(entry.path("typ").asString(""))) {
			return;
		}
		String url = trimTrailingSlash(entry.path("url").asString(""));
		String collection = entry.path("collection").asString(null);
		if (url.isEmpty() || collection == null) {
			return;
		}
		if (byUrl.containsKey(url)) {
			// Plan section 3.2: one API may hold several collections. The first one listed
			// wins -- see the class Javadoc for why that is a reported simplification, not
			// a silent one.
			return;
		}
		JsonNode firstDataset = entry.path("datasets").isArray() && !entry.path("datasets").isEmpty()
				? entry.path("datasets").get(0)
				: null;
		String rsId = firstDataset == null ? null : firstDataset.path("rs_id").asString(null);
		Map<String, String> gfiAttributes = readGfiAttributes(entry.path("gfiAttributes"));
		byUrl.put(url, new OafBinding(url, apiIdOf(url), collection, rsId, gfiAttributes));
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

	private List<GeoportalCatalogEntry> loadDatasetList(Map<String, OafBinding> oafByApiUrl) {
		byte[] csv = fetchCsvBytes();
		List<GeoportalCatalogEntry> entries = new ArrayList<>();
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
					GeoportalCatalogEntry entry = toEntry(row, columnIndex, rowNumber, oafByApiUrl);
					if (entry != null) {
						entries.add(entry);
					}
				}
				catch (RuntimeException ignored) {
					// One malformed row must not empty the whole catalog (plan section 9, point 1).
				}
			}
		}
		catch (IOException e) {
			throw new CatalogLoadException("Datensatzliste kann nicht gelesen werden", e);
		}
		return entries;
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

	private GeoportalCatalogEntry toEntry(String[] row, Map<String, Integer> columnIndex, int rowNumber,
			Map<String, OafBinding> oafByApiUrl) {
		if (!REACHABLE.equals(valueOf(row, columnIndex, "Aufrufbar"))) {
			// The other two values (plan section 3.5) mean the service sits in Hamburg's
			// internal network -- nothing this backend, running outside it, could ever reach.
			return null;
		}
		String title = valueOf(row, columnIndex, "Datensatzname");
		if (isBlank(title)) {
			return null;
		}

		String wmsAddress = valueOf(row, columnIndex, "WMS-Adresse");
		String wfsAddress = valueOf(row, columnIndex, "WFS-Adresse");
		String oafLandingPage = valueOf(row, columnIndex, "OAF-Landing Page");
		boolean hasWms = !isBlank(wmsAddress);
		boolean hasWfs = !isBlank(wfsAddress);
		boolean hasOaf = !isBlank(oafLandingPage);
		if (!hasWms && !hasWfs && !hasOaf) {
			return null; // no access path this catalog could ever offer
		}
		// "kind" follows the plan's own glossary (section 2): FEATURES means an object
		// service exists (WFS and/or OGC API Features), independent of which -- stage 1
		// only reads OGC API Features, but a WFS-only entry still belongs in the listing
		// (CONTRACT.md 11.2 counts 511, not 406) and is simply not importable yet.
		String kind = (hasWfs || hasOaf) ? (hasWms ? "BOTH" : "FEATURES") : "WMS";

		OafBinding binding = hasOaf ? oafByApiUrl.get(trimTrailingSlash(oafLandingPage)) : null;

		String organisation = valueOf(row, columnIndex, "Organisation");
		String agency = agencyOf(organisation);
		String attribution = isBlank(organisation) ? null : organisation.trim();
		String metadataUrl = blankToNull(valueOf(row, columnIndex, "Metadaten"));

		String id = binding != null
				? binding.apiId() + "/" + binding.collection()
				: "md:" + fallbackId(metadataUrl, rowNumber);

		return new GeoportalCatalogEntry(
				id,
				title.trim(),
				kind,
				agency,
				attribution,
				blankToNull(valueOf(row, columnIndex, "Kategorie")),
				metadataUrl,
				binding != null ? binding.rsId() : null,
				binding != null ? binding.apiUrl() : null,
				binding != null ? binding.collection() : null,
				binding != null ? binding.gfiAttributes() : Map.of());
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

	/** The service directory's own {@code md_id}, when a URL in this row's own columns happens to carry one; a
	 *  row number otherwise, so every entry still gets a stable, unique id within one load. */
	private static String fallbackId(String metadataUrl, int rowNumber) {
		if (metadataUrl != null) {
			Matcher matcher = MD_ID_PARAM.matcher(metadataUrl);
			if (matcher.find()) {
				return matcher.group(1).toLowerCase(Locale.ROOT);
			}
		}
		return "row-" + rowNumber;
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
