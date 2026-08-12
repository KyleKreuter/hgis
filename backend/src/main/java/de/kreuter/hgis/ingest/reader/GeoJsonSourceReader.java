package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.geotools.geojson.geom.GeometryJSON;
import org.locationtech.jts.geom.Geometry;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads GeoJSON. RFC 7946 fixed the CRS at EPSG:4326 and dropped the legacy "crs" member,
 * but plenty of data in the wild predates the RFC: some files still carry "crs", others
 * hold projected coordinates while claiming nothing at all. Both need handling, which is
 * why this reader parses the file itself with Jackson instead of trusting a library to
 * infer a schema from the first feature it happens to see.
 *
 * The declared geometry type is always the generic {@code Geometry} -- GeoJSON has no
 * per-file shape restriction the way a Shapefile does -- so the type reported in the
 * schema always comes from sampling, per the SPI's rule for formats that do not declare
 * one.
 */
final class GeoJsonSourceReader extends AbstractSourceReader {

	private static final int SAMPLE_SIZE = 1000;
	private static final Pattern EPSG_PATTERN = Pattern.compile("EPSG:{1,2}(\\d+)", Pattern.CASE_INSENSITIVE);

	private final Path file;
	private final ObjectMapper mapper = new ObjectMapper();
	private final GeometryJSON geometryJson = new GeometryJSON();
	private final List<SourceField> fields;
	private final SourceSchema schema;

	GeoJsonSourceReader(Path file, Integer sridOverride) {
		this.file = file;
		Integer legacyCrsEpsg = detectLegacyCrsEpsg();

		AttributeTypeInference attributeTypes = new AttributeTypeInference();
		List<Geometry> sampledGeometries = new ArrayList<>(SAMPLE_SIZE);
		try (JsonParser parser = openFeaturesArray()) {
			JsonNode node;
			while (sampledGeometries.size() < SAMPLE_SIZE && (node = nextFeatureNode(parser)) != null) {
				sampledGeometries.add(readGeometry(node));
				observeProperties(node, attributeTypes);
			}
		} catch (JacksonException e) {
			throw new SourceReadException("Der Import kann das GeoJSON nicht lesen: " + file, e);
		}
		this.fields = attributeTypes.fields();

		FeatureSampling.Sample sample = FeatureSampling.sample(sampledGeometries.iterator(), SAMPLE_SIZE);
		CrsDetector.Detection crs;
		if (sridOverride != null) {
			crs = CrsDetector.declared(sridOverride);
		} else if (legacyCrsEpsg != null) {
			crs = CrsDetector.declared(legacyCrsEpsg);
		} else {
			crs = CrsDetector.assumed(4326, sample.bbox());
		}

		this.schema = new SourceSchema(sample.geometryType(), crs.srid(), fields, "UTF-8", crs.confidence(), null);
	}

	@Override
	public SourceSchema schema() {
		return schema;
	}

	@Override
	public Stream<SourceFeature> features() {
		JsonParser parser = openFeaturesArray();
		Iterator<SourceFeature> iterator = new Iterator<>() {
			private SourceFeature pending = advance();

			private SourceFeature advance() {
				while (true) {
					JsonNode node;
					try {
						node = nextFeatureNode(parser);
					} catch (JacksonException e) {
						throw new SourceReadException("Der Import kann das GeoJSON nicht vollständig lesen", e);
					}
					if (node == null) {
						return null;
					}
					Geometry geometry = readGeometry(node);
					if (geometry == null || geometry.isEmpty()) {
						recordSkip();
						continue;
					}
					Map<String, Object> attributes = new LinkedHashMap<>();
					JsonNode propsNode = node.get("properties");
					for (SourceField field : fields) {
						attributes.put(field.name(), toJavaValue(propsNode == null ? null : propsNode.get(field.name())));
					}
					return new SourceFeature(geometry, attributes);
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
		return streamOf(iterator).onClose(parser::close);
	}

	@Override
	public void close() {
		// nothing persistent is held open between calls -- schema() and features() each
		// open and close their own parser over the file
	}

	// --- parsing --------------------------------------------------------------

	/**
	 * Hands back a parser positioned on the first element of the "features" array. The
	 * caller owns it from then on and closes it; every way out of here that is not that
	 * return closes it first.
	 *
	 * <p>The three rejections below are {@link SourceReadException}, an unchecked type the
	 * {@code catch} clause deliberately does not cover -- so before this was explicit they
	 * left an open parser, and with it an open file handle, that nothing held a reference to
	 * any more. A single GeoJSON {@code Feature} uploaded instead of a {@code
	 * FeatureCollection} triggers it, and the upload deliberately stays on disk for a retry:
	 * every retry leaked one more handle.
	 */
	private JsonParser openFeaturesArray() {
		InputStream in = openStream();
		JsonParser parser = null;
		try {
			parser = mapper.createParser(in);
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				throw new SourceReadException("GeoJSON ist kein Objekt: " + file.getFileName());
			}
			while (true) {
				JsonToken token = parser.nextToken();
				if (token != JsonToken.PROPERTY_NAME) {
					throw new SourceReadException("GeoJSON enthält kein 'features'-Array: " + file.getFileName());
				}
				String name = parser.currentName();
				parser.nextToken(); // move to the value
				if ("features".equals(name)) {
					if (parser.currentToken() != JsonToken.START_ARRAY) {
						throw new SourceReadException("'features' ist kein Array: " + file.getFileName());
					}
					return parser;
				}
				parser.skipChildren();
			}
		} catch (RuntimeException e) {
			// Everything reachable here is unchecked: JacksonException is a RuntimeException
			// in Jackson 3, and so are the three rejections above.
			closeQuietly(parser, in);
			throw e instanceof JacksonException
					? new SourceReadException("Der Import kann das GeoJSON nicht lesen: " + file, e)
					: e;
		}
	}

	private InputStream openStream() {
		try {
			return Files.newInputStream(file);
		} catch (IOException e) {
			throw new SourceReadException("Der Import kann das GeoJSON nicht lesen: " + file, e);
		}
	}

	/**
	 * Closes whichever of the two was reached. A parser closes its own source, so the second
	 * close is normally a no-op -- but {@code createParser} reads the first bytes to detect
	 * the encoding and can fail there, leaving a stream and no parser at all.
	 */
	private static void closeQuietly(JsonParser parser, InputStream in) {
		try (InputStream ignoredStream = in; JsonParser ignoredParser = parser) {
			// Nothing more to do: the failure that got us here is the one worth reporting.
		} catch (IOException | RuntimeException e) {
			// A close that fails must not replace the real cause.
		}
	}

	private JsonNode nextFeatureNode(JsonParser parser) {
		JsonToken token = parser.nextToken();
		if (token == JsonToken.END_ARRAY || token == null) {
			return null;
		}
		// mapper.readTree(parser) enforces "no trailing tokens", which fires immediately on
		// the very next array element or the closing bracket -- wrong for a parser that is
		// deliberately mid-stream. JsonParser#readValueAsTree() reads exactly one value
		// without that whole-document assumption.
		return parser.readValueAsTree();
	}

	private Geometry readGeometry(JsonNode featureNode) {
		JsonNode geometryNode = featureNode.get("geometry");
		if (geometryNode == null || geometryNode.isNull()) {
			return null;
		}
		try {
			return geometryJson.read(geometryNode.toString());
		} catch (IOException e) {
			return null;
		}
	}

	private static void observeProperties(JsonNode featureNode, AttributeTypeInference inference) {
		JsonNode propsNode = featureNode.get("properties");
		if (propsNode == null || !propsNode.isObject()) {
			return;
		}
		for (Map.Entry<String, JsonNode> entry : propsNode.properties()) {
			inference.observe(entry.getKey(), toJavaValue(entry.getValue()));
		}
	}

	private static Object toJavaValue(JsonNode value) {
		if (value == null || value.isNull() || value.isMissingNode()) {
			return null;
		}
		if (value.isIntegralNumber()) {
			return value.longValue();
		}
		if (value.isFloatingPointNumber()) {
			return value.doubleValue();
		}
		if (value.isBoolean()) {
			return value.booleanValue();
		}
		if (value.isTextual()) {
			return value.textValue();
		}
		return value.toString();
	}

	// --- legacy "crs" member ----------------------------------------------

	/** Scans only up to the "crs" or "features" member -- never the (possibly huge) feature array. */
	private Integer detectLegacyCrsEpsg() {
		try (JsonParser parser = mapper.createParser(Files.newInputStream(file))) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				return null;
			}
			while (true) {
				JsonToken token = parser.nextToken();
				if (token != JsonToken.PROPERTY_NAME) {
					return null;
				}
				String name = parser.currentName();
				parser.nextToken();
				if ("crs".equals(name)) {
					return parseEpsgFromCrsMember(parser.readValueAsTree());
				}
				if ("features".equals(name)) {
					return null; // "crs" conventionally precedes "features"; nothing left to find
				}
				parser.skipChildren();
			}
		} catch (IOException | JacksonException e) {
			throw new SourceReadException("Der Import kann das GeoJSON nicht lesen: " + file, e);
		}
	}

	private static Integer parseEpsgFromCrsMember(JsonNode crsNode) {
		if (crsNode == null) {
			return null;
		}
		JsonNode nameNode = crsNode.path("properties").path("name");
		if (!nameNode.isTextual()) {
			return null;
		}
		Matcher matcher = EPSG_PATTERN.matcher(nameNode.textValue());
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}
}
