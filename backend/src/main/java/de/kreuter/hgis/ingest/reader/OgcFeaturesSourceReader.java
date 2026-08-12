package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.sql.Date;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.geotools.geojson.geom.GeometryJSON;
import org.locationtech.jts.geom.Geometry;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a collection of Hamburg's OGC API Features (CONTRACT.md phase 23). {@link
 * GeoJsonSourceReader} is the template for the streaming and skip-counting; what differs
 * is that features arrive paged over HTTP rather than sitting in one file, that field names
 * come from the collection's {@code queryables} JSON Schema rather than from sampling, and
 * that the source CRS is whatever the server says it is, never what was asked for.
 *
 * <p>Public, unlike its siblings in this package: those are only ever constructed by
 * {@link SourceReaderFactory} inside this same package, but this reader is built directly
 * by {@code de.kreuter.hgis.geoportal.GeoportalImportController}, which necessarily depends
 * on {@code ingest.reader} -- never the other way round.
 *
 * <h2>Two verified quirks that shaped this class</h2>
 *
 * <p>The service's own {@code properties} query parameter -- intended for exactly the field
 * selection CONTRACT.md 11.6 asks for -- was measured live to also null out {@code
 * geometry} on every feature, for every combination tried. That looks like a bug in this
 * particular deployment, not a documented restriction, so this reader never sends it: every
 * property travels over the wire regardless of selection, and the selection is applied
 * while a feature's {@link SourceFeature} is being built instead. It costs bandwidth on a
 * dataset with many unwanted columns; it does not risk silently dropping every geometry.
 *
 * <p>The field carrying {@code x-ogc-role: id} (decision E6) is not one of the properties
 * a plain items request returns -- it only ever appears as the GeoJSON {@code Feature}'s own
 * top-level {@code id}, confirmed against the tree cadastre's {@code gid}. It is read from
 * there, never from {@code properties}, and -- also measured live -- stops appearing there
 * at all once a {@code properties} parameter is present without naming it, which is the
 * other reason this reader never uses that parameter.
 */
public final class OgcFeaturesSourceReader extends AbstractSourceReader {

	/** The server accepts a higher {@code limit} but silently caps the page at this (plan section 3.2). */
	static final int DEFAULT_PAGE_SIZE = 10_000;

	/** Same sampling budget {@link GeoJsonSourceReader} uses to type a format that declares no schema of its own. */
	private static final int SAMPLE_SIZE = 1000;

	private static final String CRS_25832_URI = "http://www.opengis.net/def/crs/EPSG/0/25832";
	private static final Pattern CONTENT_CRS_EPSG = Pattern.compile("EPSG/0/(\\d+)");

	private final RestClient restClient;
	private final String apiUrl;
	private final String collection;
	private final double[] bbox4326;
	private final int pageSize;
	private final ObjectMapper mapper = new ObjectMapper();
	private final GeometryJSON geometryJson = new GeometryJSON();

	/** Every field the reader will emit, id field always included (E6) whether or not it was selected. */
	private final List<QueryablesSchema.Field> fields;

	/** Index into {@link #fields} of the {@code x-ogc-role: id} field, or -1 if the collection has none. */
	private final int idFieldIndex;

	private final SourceSchema schema;

	/**
	 * Page 0, fetched once during construction to answer {@link #schema()} (geometry
	 * family, the server's actual CRS, the matched count) and handed out again -- not
	 * re-fetched -- as the start of {@link #features()}, so the two phases together never
	 * download the same page twice.
	 */
	private final List<SourceFeature> firstPage;

	/** Whether the collection offers EPSG:25832, decided once from the collection's own {@code crs} list. */
	private final boolean requestCrs25832;

	/**
	 * @param restClient     already configured with timeouts and a User-Agent; this class
	 *                       adds no HTTP concerns of its own beyond the request itself
	 * @param apiUrl         the API's landing page, e.g.
	 *                       {@code https://api.hamburg.de/datasets/v1/strassenbaumkataster}
	 *                       -- no trailing slash required
	 * @param collection     the collection id within that API, e.g. {@code strassenbaumkataster_hh}
	 * @param bbox4326       {@code [minLng, minLat, maxLng, maxLat]} in EPSG:4326, or null
	 *                       for the whole dataset (CONTRACT.md 11.6)
	 * @param fieldSelection technical field names to keep, or null for every field
	 *                       (CONTRACT.md 11.6); the id field is kept regardless (decision E6)
	 * @param germanLabels   the service directory's {@code gfiAttributes} for this
	 *                       collection, technical name to German label, or null/empty when
	 *                       the directory carries none
	 * @throws BadRequestException if {@code fieldSelection} names a field the collection
	 *                              does not have
	 */
	public OgcFeaturesSourceReader(RestClient restClient, String apiUrl, String collection, double[] bbox4326,
			List<String> fieldSelection, Map<String, String> germanLabels) {
		this(restClient, apiUrl, collection, bbox4326, fieldSelection, germanLabels, DEFAULT_PAGE_SIZE);
	}

	/**
	 * Same as the public constructor, with the page size exposed -- production code never
	 * calls this one directly (it always gets {@link #DEFAULT_PAGE_SIZE}, the server's own
	 * hard cap). Tests use it to prove the paging logic itself -- offset advances
	 * correctly, every object arrives, none twice -- against a handful of features instead
	 * of the 10,000 a real page holds, which OgcFeaturesSourceReaderTest's other cases
	 * would make unworkable as a literal fixture.
	 */
	OgcFeaturesSourceReader(RestClient restClient, String apiUrl, String collection, double[] bbox4326,
			List<String> fieldSelection, Map<String, String> germanLabels, int pageSize) {
		this.restClient = restClient;
		this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
		this.collection = collection;
		this.bbox4326 = bbox4326;
		this.pageSize = pageSize;

		this.requestCrs25832 = fetchCollectionCrsUris().contains(CRS_25832_URI);
		JsonNode queryables = fetchQueryables();
		List<QueryablesSchema.Field> allFields =
				QueryablesSchema.parse(queryables, germanLabels == null ? Map.of() : germanLabels);
		this.fields = selectFields(allFields, fieldSelection);
		this.idFieldIndex = indexOfIdField(fields);

		ItemsPage page0 = fetchItemsPage(0);
		this.firstPage = page0.features();

		FeatureSampling.Sample sample = FeatureSampling.sample(
				firstPage.stream().map(SourceFeature::geometry).iterator(), SAMPLE_SIZE);

		List<SourceField> sourceFields = fields.stream()
				.map(f -> new SourceField(f.title(), f.javaType()))
				.toList();
		this.schema = new SourceSchema(sample.geometryType(), page0.sourceSrid(), sourceFields, "UTF-8",
				SourceSchema.CrsConfidence.DECLARED, page0.numberMatched());
	}

	@Override
	public SourceSchema schema() {
		return schema;
	}

	@Override
	public Stream<SourceFeature> features() {
		Iterator<SourceFeature> iterator = new Iterator<>() {
			private Iterator<SourceFeature> currentPage = firstPage.iterator();
			private int nextOffset = firstPage.size();
			private boolean morePagesMayExist = firstPage.size() >= pageSize;
			private SourceFeature pending = advance();

			private SourceFeature advance() {
				while (true) {
					if (currentPage.hasNext()) {
						return currentPage.next();
					}
					if (!morePagesMayExist) {
						return null;
					}
					ItemsPage page = fetchItemsPage(nextOffset);
					nextOffset += page.rawCount();
					morePagesMayExist = page.rawCount() >= pageSize;
					currentPage = page.features().iterator();
				}
			}

			@Override
			public boolean hasNext() {
				return pending != null;
			}

			@Override
			public SourceFeature next() {
				if (pending == null) {
					throw new NoSuchElementException();
				}
				SourceFeature result = pending;
				pending = advance();
				return result;
			}
		};
		return streamOf(iterator);
	}

	@Override
	public void close() {
		// Nothing persistent is held open between calls: schema() already ran to
		// completion in the constructor, and features() opens and closes one HTTP
		// exchange per page as it goes.
	}

	// --- field selection --------------------------------------------------------------

	/**
	 * @throws BadRequestException a requested technical name does not exist on this
	 *                              collection (CONTRACT.md 11.6: "A name not in the
	 *                              dataset is a 400")
	 */
	private static List<QueryablesSchema.Field> selectFields(List<QueryablesSchema.Field> allFields,
			List<String> requested) {
		if (requested == null) {
			return allFields;
		}
		Map<String, QueryablesSchema.Field> byTechnicalName = new LinkedHashMap<>();
		for (QueryablesSchema.Field field : allFields) {
			byTechnicalName.put(field.technicalName(), field);
		}
		for (String name : requested) {
			if (!byTechnicalName.containsKey(name)) {
				throw new BadRequestException("Unbekanntes Feld: " + name);
			}
		}
		Set<String> wanted = new LinkedHashSet<>(requested);
		List<QueryablesSchema.Field> selected = new ArrayList<>();
		for (QueryablesSchema.Field field : allFields) {
			// The id field travels along even when the caller never named it -- decision
			// E6 depends on every import writing it, not only the ones that asked for it.
			if (wanted.contains(field.technicalName()) || field.idField()) {
				selected.add(field);
			}
		}
		return selected;
	}

	private static int indexOfIdField(List<QueryablesSchema.Field> fields) {
		for (int i = 0; i < fields.size(); i++) {
			if (fields.get(i).idField()) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * The two extra parameters {@code de.kreuter.hgis.common.TableCreator}'s wider
	 * overload needs, in the same order as {@link #schema()}{@code .fields()}: the
	 * technical name to derive {@code column_name} from (decision E1, since {@code
	 * source_name} carries the German title instead), and which entry is the id field to
	 * index (decision E6). Not part of {@link de.kreuter.hgis.ingest.spi.SourceReader} --
	 * that interface is shared with every file-based reader and carries no such concept --
	 * so the caller reads them straight off this concrete type before it hands the reader
	 * to {@code ImportService}.
	 */
	public List<String> columnNameBasis() {
		return fields.stream().map(QueryablesSchema.Field::technicalName).toList();
	}

	public int idFieldIndex() {
		return idFieldIndex;
	}

	// --- HTTP: collection metadata and queryables --------------------------------------

	private Set<String> fetchCollectionCrsUris() {
		URI uri = UriComponentsBuilder.fromUriString(apiUrl)
				.pathSegment("collections", collection)
				.queryParam("f", "json")
				.build()
				.encode()
				.toUri();
		JsonNode body = getJson(uri);
		Set<String> uris = new LinkedHashSet<>();
		JsonNode crsNode = body.path("crs");
		if (crsNode.isArray()) {
			for (JsonNode value : crsNode) {
				uris.add(value.asString());
			}
		}
		return uris;
	}

	private JsonNode fetchQueryables() {
		URI uri = UriComponentsBuilder.fromUriString(apiUrl)
				.pathSegment("collections", collection, "queryables")
				.queryParam("f", "json")
				.build()
				.encode()
				.toUri();
		return getJson(uri);
	}

	private JsonNode getJson(URI uri) {
		return restClient.get().uri(uri).exchange((request, response) -> {
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new SourceReadException("Geoportal antwortete mit " + response.getStatusCode()
						+ " auf " + uri);
			}
			try (InputStream body = response.getBody()) {
				return mapper.readTree(body);
			}
		});
	}

	// --- HTTP: items, paged -------------------------------------------------------------

	private record ItemsPage(List<SourceFeature> features, int rawCount, int sourceSrid, long numberMatched) {
	}

	private ItemsPage fetchItemsPage(int offset) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(apiUrl)
				.pathSegment("collections", collection, "items")
				.queryParam("f", "json")
				.queryParam("limit", pageSize)
				.queryParam("offset", offset);
		if (requestCrs25832) {
			builder.queryParam("crs", CRS_25832_URI);
		}
		if (bbox4326 != null) {
			builder.queryParam("bbox", bbox4326[0] + "," + bbox4326[1] + "," + bbox4326[2] + "," + bbox4326[3]);
		}
		URI uri = builder.build().encode().toUri();

		return restClient.get().uri(uri).exchange((request, response) -> {
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new SourceReadException("Geoportal antwortete mit " + response.getStatusCode()
						+ " auf " + uri);
			}
			int sourceSrid = parseContentCrs(response.getHeaders().getFirst("Content-Crs"));
			try (InputStream body = response.getBody()) {
				return parseItemsPage(body, sourceSrid);
			}
		});
	}

	/**
	 * Streams the items document with a raw parser rather than {@code readTree}-ing the
	 * whole page, mirroring {@link GeoJsonSourceReader}: a page can be several megabytes,
	 * and only one feature at a time needs to be a materialised tree.
	 */
	private ItemsPage parseItemsPage(InputStream body, int sourceSrid) {
		try (JsonParser parser = mapper.createParser(body)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				throw new SourceReadException("Antwort des Geoportals ist kein JSON-Objekt");
			}
			long numberMatched = 0;
			List<SourceFeature> features = new ArrayList<>();
			int rawCount = 0;
			while (true) {
				JsonToken token = parser.nextToken();
				if (token != JsonToken.PROPERTY_NAME) {
					break;
				}
				String name = parser.currentName();
				parser.nextToken();
				if ("numberMatched".equals(name)) {
					JsonNode value = parser.readValueAsTree();
					numberMatched = value.asLong();
				}
				else if ("features".equals(name)) {
					if (parser.currentToken() != JsonToken.START_ARRAY) {
						throw new SourceReadException("'features' ist im Geoportal-Ergebnis kein Array");
					}
					while (true) {
						JsonToken elementToken = parser.nextToken();
						if (elementToken == JsonToken.END_ARRAY || elementToken == null) {
							break;
						}
						rawCount++;
						SourceFeature feature = parseFeature(parser.readValueAsTree());
						if (feature != null) {
							features.add(feature);
						}
					}
				}
				else {
					parser.skipChildren();
				}
			}
			return new ItemsPage(features, rawCount, sourceSrid, numberMatched);
		}
		catch (JacksonException e) {
			throw new SourceReadException("Der Import kann die Antwort des Geoportals nicht lesen", e);
		}
	}

	/** Null on a missing or unreadable geometry -- the caller counts it as skipped, same rule as every other reader. */
	private SourceFeature parseFeature(JsonNode featureNode) {
		try {
			JsonNode geometryNode = featureNode.get("geometry");
			if (geometryNode == null || geometryNode.isNull()) {
				recordSkip();
				return null;
			}
			Geometry geometry = geometryJson.read(geometryNode.toString());
			if (geometry == null || geometry.isEmpty()) {
				recordSkip();
				return null;
			}

			JsonNode propsNode = featureNode.get("properties");
			JsonNode idNode = featureNode.get("id");
			Map<String, Object> attributes = new LinkedHashMap<>();
			for (QueryablesSchema.Field field : fields) {
				// The id-role field is a top-level GeoJSON Feature.id, never a member of
				// properties -- see the class-level note on the two verified quirks.
				JsonNode valueNode = field.idField() ? idNode : (propsNode == null ? null : propsNode.get(field.technicalName()));
				attributes.put(field.title(), convertValue(valueNode, field.javaType()));
			}
			return new SourceFeature(geometry, attributes);
		}
		catch (IOException | RuntimeException e) {
			// A single malformed value (an unparseable date, a geometry GeoTools rejects)
			// must not abort the whole import -- the same rule GeoJsonSourceReader and
			// AbstractSourceReader's GeoTools path both already follow.
			recordSkip();
			return null;
		}
	}

	private static Object convertValue(JsonNode node, Class<?> javaType) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (javaType == Long.class) {
			return node.asLong();
		}
		if (javaType == Double.class) {
			return node.asDouble();
		}
		if (javaType == Boolean.class) {
			return node.asBoolean();
		}
		if (javaType == Date.class) {
			return Date.valueOf(node.asString());
		}
		if (javaType == Instant.class) {
			return Instant.parse(node.asString());
		}
		// String, and the array/object case (plan 6.3 step 2): the value's own JSON text.
		return node.isString() ? node.asString() : node.toString();
	}

	/**
	 * CONTRACT.md 11.8: believe this header over whatever CRS was requested -- a service
	 * that silently answers in a different system is otherwise imported at the wrong place
	 * on earth. A missing header means CRS84, i.e. EPSG:4326.
	 */
	private static int parseContentCrs(String headerValue) {
		if (headerValue == null || headerValue.isBlank()) {
			return 4326;
		}
		Matcher matcher = CONTENT_CRS_EPSG.matcher(headerValue);
		// CRS84 itself carries no EPSG code in its URI (OGC/1.3/CRS84), so a header naming
		// it falls through to the same 4326 a missing header would give -- CRS84 and
		// EPSG:4326 share an axis order and an extent, which is what makes that safe here.
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 4326;
	}
}
