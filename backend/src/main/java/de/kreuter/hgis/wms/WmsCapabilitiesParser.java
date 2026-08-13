package de.kreuter.hgis.wms;

import de.kreuter.hgis.common.UnprocessableEntityException;
import de.kreuter.hgis.wms.dto.WmsDtos;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads a WMS GetCapabilities document with the JDK's own DOM parser -- no dependency
 * added for it, the same way {@code geoportal.CatalogLoader} reads a 7.6 MB JSON file
 * with the JDK's streaming parser instead of pulling in a schema-generated binding.
 *
 * <h2>What "the root" means for the EPSG:3857 check</h2>
 *
 * <p>CONTRACT.md asks whether EPSG:3857 is in "the CRS list of the root" -- the first
 * {@code <Layer>} directly under {@code <Capability>}. Measured against all four of the
 * fixtures this class is tested against, that element always carries its service's full
 * CRS list (Hamburg's own services declare it once there and let every child inherit),
 * so reading only its own, undeclared-by-inheritance {@code <CRS>} children is enough --
 * no accumulation from the rest of the tree is needed for this one check.
 *
 * <h2>Flattening the layer tree ({@link WmsDtos.Layer#depth()})</h2>
 *
 * <p>Three things were measured live, against the same four fixtures, and all three had
 * to hold at once for the shape below to fit:
 *
 * <ul>
 * <li><strong>{@code HH_WMS_Cache_Stadtplan}</strong> has exactly one {@code <Layer>} in
 * the whole document -- the one directly under {@code <Capability>}, which both is named
 * and has no children. It has to end up listed, or a service with only one layer would
 * offer none at all.</li>
 * <li><strong>{@code HH_WMS_Geobasiskarten}</strong>'s outermost {@code <Layer>} is also
 * named ({@code wms_geobasiskarten_n}) but this time has children -- one named group
 * ({@code geobasiskarten_farbig}) holding several named, scale-limited children of its
 * own ({@code m2500_farbig} and seven more). Listing the outermost element here too
 * would put the service's own container next to the real choices a user has, one
 * meaningless entry above everything else every single service would carry.</li>
 * <li><strong>{@code HH_WMS_Fachdaten_ALKIS}</strong> nests the same way, several levels
 * deep, except every one of its grouping layers is unnamed -- only the leaves carry a
 * {@code <Name>}. CONTRACT.md says an unnamed group "is not listed", but says nothing
 * about what that does to the leaves' {@code depth}: collapsing it (counting only named
 * ancestors) would put every single layer of this service at depth 0, indistinguishable
 * from a flat list -- which defeats the one thing this fixture was chosen to exercise.
 * Depth here therefore counts every {@code <Layer>} boundary crossed, named or not; an
 * unnamed group still contributes no entry of its own, only a nesting level for what is
 * inside it.</li>
 * </ul>
 *
 * <p>The rule that satisfies all three: the outermost {@code <Layer>} is treated as the
 * service's own container and excluded from the list <em>whenever it has children</em> --
 * its children then start the flat list at depth 0, and depth increases by one for every
 * further {@code <Layer>} nesting level regardless of whether the layer crossed has a
 * name. Only when the outermost element has no children at all does it become the list's
 * one entry, at depth 0, since there is then nothing else to offer.
 *
 * <p>This is the one real interpretive call this class makes beyond what CONTRACT.md
 * spells out -- flagged in the implementation report for the orchestrator to confirm
 * against the frontend's actual indentation, since CONTRACT.md's own example (a
 * two-entry excerpt, depth 0 and 1) is consistent with either reading and does not
 * distinguish them on its own.
 */
final class WmsCapabilitiesParser {

	/** CONTRACT.md: any other version is rejected before anything else about the document is read. */
	private static final String SUPPORTED_VERSION = "1.3.0";

	private static final String REQUIRED_CRS = "EPSG:3857";

	private static final String SERVICE_UNREADABLE = "Der Dienst hat nicht geantwortet.";

	private WmsCapabilitiesParser() {
	}

	/** Depth-independent scale/bbox state a layer either declares itself or inherits from its nearest ancestor. */
	private record Inherited(double[] bbox, Double minScale, Double maxScale) {
	}

	static WmsDtos.CapabilitiesResponse parse(byte[] xml, String serviceUrl) {
		Element root = parseXml(xml);

		String version = root.getAttribute("version");
		if (!SUPPORTED_VERSION.equals(version)) {
			throw new UnprocessableEntityException("Dieser Dienst spricht WMS "
					+ (version.isBlank() ? "in einer unbekannten Version" : version)
					+ ". hGIS unterstützt nur 1.3.0.");
		}

		Element serviceEl = child(root, "Service");
		String title = serviceEl == null ? null : textOf(child(serviceEl, "Title"));

		Element capabilityEl = child(root, "Capability");
		if (capabilityEl == null) {
			throw new WmsUnavailableException(SERVICE_UNREADABLE);
		}
		Element rootLayerEl = child(capabilityEl, "Layer");
		if (rootLayerEl == null) {
			throw new WmsUnavailableException(SERVICE_UNREADABLE);
		}

		if (!crsOf(rootLayerEl).contains(REQUIRED_CRS)) {
			throw new UnprocessableEntityException("Dieser Dienst liefert keine Karten in Web-Mercator "
					+ "(EPSG:3857). hGIS kann ihn nicht anzeigen.");
		}

		List<String> imageFormats = mapFormatsOf(capabilityEl);
		List<WmsDtos.Layer> layers = extractLayers(rootLayerEl);

		return new WmsDtos.CapabilitiesResponse(serviceUrl, title, version, imageFormats, layers);
	}

	// --- layer tree ---------------------------------------------------------------------

	/** See the class doc for why the root is excluded exactly when it has children. */
	private static List<WmsDtos.Layer> extractLayers(Element rootLayerEl) {
		List<Element> children = childElements(rootLayerEl, "Layer");
		List<WmsDtos.Layer> out = new ArrayList<>();

		if (children.isEmpty()) {
			String name = textOf(child(rootLayerEl, "Name"));
			if (name != null && !name.isBlank()) {
				out.add(buildEntry(rootLayerEl, name, 0, bboxOf(rootLayerEl),
						scaleOf(rootLayerEl, "MinScaleDenominator"), scaleOf(rootLayerEl, "MaxScaleDenominator")));
			}
			return out;
		}

		Inherited fromRoot = new Inherited(bboxOf(rootLayerEl),
				scaleOf(rootLayerEl, "MinScaleDenominator"), scaleOf(rootLayerEl, "MaxScaleDenominator"));
		for (Element child : children) {
			walk(child, 0, fromRoot, out);
		}
		return out;
	}

	private static void walk(Element layerEl, int depth, Inherited inherited, List<WmsDtos.Layer> out) {
		double[] ownBbox = bboxOf(layerEl);
		Double ownMinScale = scaleOf(layerEl, "MinScaleDenominator");
		Double ownMaxScale = scaleOf(layerEl, "MaxScaleDenominator");

		double[] effectiveBbox = ownBbox != null ? ownBbox : inherited.bbox();
		Double effectiveMinScale = ownMinScale != null ? ownMinScale : inherited.minScale();
		Double effectiveMaxScale = ownMaxScale != null ? ownMaxScale : inherited.maxScale();

		String name = textOf(child(layerEl, "Name"));
		if (name != null && !name.isBlank()) {
			out.add(buildEntry(layerEl, name, depth, effectiveBbox, effectiveMinScale, effectiveMaxScale));
		}

		Inherited nextInherited = new Inherited(effectiveBbox, effectiveMinScale, effectiveMaxScale);
		for (Element child : childElements(layerEl, "Layer")) {
			walk(child, depth + 1, nextInherited, out);
		}
	}

	private static WmsDtos.Layer buildEntry(Element layerEl, String name, int depth, double[] bbox,
			Double minScale, Double maxScale) {
		String title = textOf(child(layerEl, "Title"));
		boolean queryable = "1".equals(layerEl.getAttribute("queryable"));
		return new WmsDtos.Layer(name, title != null && !title.isBlank() ? title : name, depth, queryable,
				legendUrlOf(layerEl), minScale, maxScale, bbox);
	}

	/**
	 * The first {@code <Style>}'s {@code <LegendURL>}, own declaration only -- not
	 * inherited, unlike bbox and scale: a legend image describes one layer's own
	 * symbology, and a group's would be misleading on a layer that never draws with it.
	 */
	private static String legendUrlOf(Element layerEl) {
		Element style = child(layerEl, "Style");
		if (style == null) {
			return null;
		}
		Element legend = child(style, "LegendURL");
		if (legend == null) {
			return null;
		}
		Element onlineResource = child(legend, "OnlineResource");
		if (onlineResource == null) {
			return null;
		}
		String href = onlineResource.getAttribute("xlink:href");
		return href.isBlank() ? null : href;
	}

	/** {@code [minLng, minLat, maxLng, maxLat]} in EPSG:4326, or null when this element declares none of its own. */
	private static double[] bboxOf(Element layerEl) {
		Element bbox = child(layerEl, "EX_GeographicBoundingBox");
		if (bbox == null) {
			return null;
		}
		Double west = doubleOf(child(bbox, "westBoundLongitude"));
		Double south = doubleOf(child(bbox, "southBoundLatitude"));
		Double east = doubleOf(child(bbox, "eastBoundLongitude"));
		Double north = doubleOf(child(bbox, "northBoundLatitude"));
		if (west == null || south == null || east == null || north == null) {
			return null;
		}
		return new double[] { west, south, east, north };
	}

	private static Double scaleOf(Element layerEl, String tagName) {
		return doubleOf(child(layerEl, tagName));
	}

	/** Every {@code <CRS>} this element declares directly, upper-cased -- no inheritance, see the class doc. */
	private static Set<String> crsOf(Element layerEl) {
		Set<String> result = new LinkedHashSet<>();
		for (Element crs : childElements(layerEl, "CRS")) {
			String text = textOf(crs);
			if (text != null) {
				result.add(text.toUpperCase(Locale.ROOT));
			}
		}
		return result;
	}

	private static List<String> mapFormatsOf(Element capabilityEl) {
		Element request = child(capabilityEl, "Request");
		if (request == null) {
			return List.of();
		}
		Element getMap = child(request, "GetMap");
		if (getMap == null) {
			return List.of();
		}
		List<String> formats = new ArrayList<>();
		for (Element format : childElements(getMap, "Format")) {
			String text = textOf(format);
			if (text != null) {
				formats.add(text);
			}
		}
		return formats;
	}

	// --- small DOM helpers ----------------------------------------------------------------

	/** The first direct child element with this tag name, or null. Never descends past one level. */
	private static Element child(Element parent, String tagName) {
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node node = children.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
				return (Element) node;
			}
		}
		return null;
	}

	/** Every direct child element with this tag name, in document order. */
	private static List<Element> childElements(Element parent, String tagName) {
		List<Element> result = new ArrayList<>();
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node node = children.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
				result.add((Element) node);
			}
		}
		return result;
	}

	/** Trimmed text content, or null for a missing element or one with only blank text. */
	private static String textOf(Element element) {
		if (element == null) {
			return null;
		}
		String text = element.getTextContent();
		if (text == null) {
			return null;
		}
		String trimmed = text.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static Double doubleOf(Element element) {
		String text = textOf(element);
		if (text == null) {
			return null;
		}
		try {
			return Double.parseDouble(text);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * XXE-safe by construction: DOCTYPE declarations are refused outright rather than
	 * merely having external entity resolution disabled, which is the stronger of the
	 * two OWASP-recommended defences and costs nothing here -- a WMS capabilities
	 * document has no legitimate reason to declare one.
	 */
	private static Element parseXml(byte[] xml) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			factory.setXIncludeAware(false);
			// Namespace-unaware on purpose: every WMS-defined element in a Hamburg
			// capabilities document (Layer, Name, CRS, ...) carries no prefix, only the
			// xlink attributes do, and a plain, unqualified tag/attribute comparison
			// reads both correctly without resolving the declared xmlns at all.
			factory.setNamespaceAware(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(new ByteArrayInputStream(xml));
			return document.getDocumentElement();
		}
		catch (ParserConfigurationException | SAXException | IOException | IllegalArgumentException e) {
			throw new WmsUnavailableException(SERVICE_UNREADABLE);
		}
	}
}
