package de.kreuter.hgis.places;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Streams a Hamburg WFS GetFeature response (dog:Strassen or dog:Ortsteile, GML 3.2 wrapped
 * in a WFS 2.0 FeatureCollection) into {@link ParsedPlace} rows, one member at a time.
 *
 * <p>StAX rather than the DOM {@code wms.WmsCapabilitiesParser} uses for its few-KB
 * capabilities documents: the full Strassen extract is 32&nbsp;MB (measured live, 9534
 * members), and CONTRACT.md points at {@code geoportal.CatalogLoader}'s streaming read of a
 * 7.6&nbsp;MB JSON file as the yardstick for when a tree built wholesale in memory stops
 * being the right call. A malformed member is not defended against the way CatalogLoader
 * defends against one malformed catalog entry: this is a single, schema-validated
 * government WFS rather than 508 independent, heterogeneous services, and a reader that
 * tries to resynchronise mid-element risks reading structurally wrong data silently rather
 * than failing loudly -- so a parse failure here fails the whole refresh, reported through
 * {@link PlaceRefreshException} and left for the job's own failure message.
 *
 * <p>Parsed namespace-unaware, like {@code WmsCapabilitiesParser} -- but for the opposite
 * reason. That class's document leaves every WMS-defined element unprefixed; this one
 * prefixes everything that matters (dog:, iso19112:, gml:) consistently within one member,
 * so comparing tag names as plain strings including their prefix is correct and needs no
 * namespace resolution at all. Confirmed against the JDK's own StAX implementation:
 * {@link XMLStreamReader#getLocalName()} returns the prefixed name verbatim once namespace
 * awareness is switched off.
 */
@Component
class PlaceGmlReader {

	private static final Logger log = LoggerFactory.getLogger(PlaceGmlReader.class);

	private static final String STRASSEN = "dog:Strassen";
	private static final String ORTSTEILE = "dog:Ortsteile";
	private static final String STRASSENNAME = "dog:strassenname";
	private static final String POST_ORTSTEIL = "dog:postOrtsteil";
	private static final String POSTLEITZAHL = "dog:postleitzahl";
	private static final String PARENT = "iso19112:parent";
	private static final String POSITION = "iso19112:position";
	private static final String POS = "gml:pos";

	/** One {@code dog:Strassen} or {@code dog:Ortsteile} member, before it is turned into
	 *  one or more {@link ParsedPlace} rows. */
	private record RawFeature(String name, List<String> postOrtsteil, List<String> postleitzahl,
			String parent, double[] pos25832) {
	}

	List<ParsedPlace> readStrassen(InputStream gml) {
		return read(gml, STRASSEN, this::toStreetPlaces);
	}

	List<ParsedPlace> readOrtsteile(InputStream gml) {
		return read(gml, ORTSTEILE, this::toDistrictPlaces);
	}

	private List<ParsedPlace> read(InputStream gml, String featureTag,
			java.util.function.Function<RawFeature, List<ParsedPlace>> toPlaces) {
		List<ParsedPlace> result = new ArrayList<>();
		int memberCount = 0;
		try {
			XMLStreamReader reader = createFactory().createXMLStreamReader(gml);
			try {
				while (reader.hasNext()) {
					int event = reader.next();
					if (event == XMLStreamConstants.START_ELEMENT && featureTag.equals(reader.getLocalName())) {
						result.addAll(toPlaces.apply(readFeature(reader)));
						memberCount++;
					}
				}
			}
			finally {
				reader.close();
			}
		}
		catch (XMLStreamException e) {
			throw new PlaceRefreshException(
					"Der Abzug von Hamburg (" + featureTag + ") lässt sich nicht lesen: " + e.getMessage(), e);
		}
		log.debug("Read {} {} members, {} places", memberCount, featureTag, result.size());
		return result;
	}

	/**
	 * Reads one feature's children up to its own end tag, positioned on the feature's
	 * START_ELEMENT when called. {@code depth} starts at 1 for that already-open element
	 * and the loop runs until it falls back to 0 -- i.e. until this element's own
	 * END_ELEMENT, not one of its descendants'.
	 *
	 * <p>{@link XMLStreamReader#getElementText()} is used for every leaf field: it reads
	 * from a START_ELEMENT straight through to the matching END_ELEMENT in one call, so
	 * that pair never reaches this loop's own event switch and never has to be counted
	 * towards {@code depth} -- entering and fully leaving a subtree that way is
	 * depth-neutral by construction. {@code iso19112:position} cannot be read this way --
	 * it has element children of its own (a comment and a {@code gml:Point}), which
	 * {@code getElementText()} refuses -- so it is tracked with a plain boolean instead,
	 * scoped by the ordinary START_ELEMENT/END_ELEMENT pair every other unrecognised
	 * element also goes through.
	 */
	private static RawFeature readFeature(XMLStreamReader reader) throws XMLStreamException {
		String name = null;
		String parent = null;
		List<String> postOrtsteil = new ArrayList<>();
		List<String> postleitzahl = new ArrayList<>();
		double[] pos = null;
		boolean inPosition = false;
		int depth = 1;

		while (depth > 0) {
			int event = reader.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				String tag = reader.getLocalName();
				switch (tag) {
					case STRASSENNAME -> {
						if (name == null) {
							name = trimmed(reader.getElementText());
						}
					}
					case PARENT -> {
						if (parent == null) {
							parent = trimmed(reader.getElementText());
						}
					}
					case POST_ORTSTEIL -> postOrtsteil.add(trimmed(reader.getElementText()));
					case POSTLEITZAHL -> postleitzahl.add(trimmed(reader.getElementText()));
					case POSITION -> {
						inPosition = true;
						depth++;
					}
					case POS -> {
						// iso19112:position_strassenachse also wraps a gml:Point/gml:pos --
						// CONTRACT.md's own worked example (572406.785 5937005.370, taken
						// from iso19112:position) is what fixes which of the two this
						// reader must use, so position_strassenachse's is deliberately
						// left uncaptured (inPosition stays false for it).
						String text = trimmed(reader.getElementText());
						if (inPosition && pos == null) {
							pos = parsePos(text);
						}
					}
					default -> depth++;
				}
			}
			else if (event == XMLStreamConstants.END_ELEMENT) {
				if (POSITION.equals(reader.getLocalName())) {
					inPosition = false;
				}
				depth--;
			}
		}
		return new RawFeature(name, postOrtsteil, postleitzahl, parent, pos);
	}

	/**
	 * One row per postal-code segment (V10__place.sql's class doc explains why). Hamburg's
	 * WFS repeats its whole {@code dog:postleitzahl} list a second time, verbatim, right
	 * after {@code dog:postOrtsteil} -- measured on every one of the 9534 live members --
	 * while {@code dog:postOrtsteil} itself is not doubled. Only the first half of {@code
	 * postleitzahl} therefore lines up, index for index, with {@code postOrtsteil}'s real
	 * segments; reading all of it would double-count every street.
	 */
	private List<ParsedPlace> toStreetPlaces(RawFeature raw) {
		if (raw.name() == null || raw.pos25832() == null) {
			return List.of();
		}
		int segments = Math.min(raw.postOrtsteil().size(), raw.postleitzahl().size() / 2);
		if (segments == 0) {
			// Measured live (2 of 9534 members, e.g. "Herulerweg"): a street with no postal
			// code on file at all. Still a real, findable street -- just without a context.
			return List.of(new ParsedPlace(raw.name(), null, "street", raw.pos25832()[0], raw.pos25832()[1]));
		}
		List<ParsedPlace> places = new ArrayList<>(segments);
		for (int i = 0; i < segments; i++) {
			String context = raw.postOrtsteil().get(i) + ", " + raw.postleitzahl().get(i);
			places.add(new ParsedPlace(raw.name(), context, "street", raw.pos25832()[0], raw.pos25832()[1]));
		}
		return places;
	}

	/** One row per district, named from {@code iso19112:parent} -- the clean form
	 *  ("Hamburg-Altstadt"), unlike {@code dog:ortsteilname} which carries a ",OT nnnn"
	 *  suffix meant for machine keys, not display. */
	private List<ParsedPlace> toDistrictPlaces(RawFeature raw) {
		if (raw.parent() == null || raw.pos25832() == null) {
			return List.of();
		}
		return List.of(new ParsedPlace(raw.parent(), null, "district", raw.pos25832()[0], raw.pos25832()[1]));
	}

	/** {@code "579684.552 5927090.528"} -> easting/northing. Null, not an exception, for
	 *  anything that does not parse -- the caller already treats a missing position as a
	 *  feature to skip, the same way it treats a missing name. */
	private static double[] parsePos(String text) {
		String[] parts = text.split("\\s+");
		if (parts.length != 2) {
			return null;
		}
		try {
			return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) };
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static String trimmed(String text) {
		if (text == null) {
			return null;
		}
		String trimmed = text.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * XXE-safe by construction, the same defence {@code WmsCapabilitiesParser} applies to
	 * its DOM parser: external entities and DTDs are refused outright rather than merely
	 * disabled after the fact.
	 */
	private static XMLInputFactory createFactory() {
		XMLInputFactory factory = XMLInputFactory.newInstance();
		factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		return factory;
	}
}
