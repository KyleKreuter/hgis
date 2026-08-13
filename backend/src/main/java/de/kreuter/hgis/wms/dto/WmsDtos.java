package de.kreuter.hgis.wms.dto;

import java.util.List;

/**
 * Transport types for reading a WMS service's own capabilities document (plan
 * "Kartenbilder aus dem Geoportal Hamburg", stage 2). Grouped in one file the same way
 * as {@code LayerDtos} and {@code GeoportalDtos}.
 */
public final class WmsDtos {

	private WmsDtos() {
	}

	/**
	 * Answer to {@code GET /api/wms/capabilities}.
	 *
	 * @param serviceUrl   the address as given, without any query parameters -- the
	 *                     same shape a client is expected to send back for a GetMap
	 *                     request it builds itself
	 * @param title        the service's own title, or null when it names none
	 * @param version      the WMS version the service answered with; always
	 *                     {@code "1.3.0"} here, since anything else was already
	 *                     rejected with 422 before this response is built
	 * @param imageFormats every {@code Format} the service's {@code GetMap} operation
	 *                     offers, in document order
	 * @param layers       every named layer, flattened -- see {@link Layer#depth()}
	 */
	public record CapabilitiesResponse(
			String serviceUrl,
			String title,
			String version,
			List<String> imageFormats,
			List<Layer> layers) {
	}

	/**
	 * One named layer of the service, flattened out of the document's nested
	 * {@code <Layer>} tree.
	 *
	 * @param name      the technical name a GetMap request's {@code LAYERS} parameter
	 *                  uses; only an element that names one is ever listed -- an
	 *                  unnamed grouping layer is transparent and contributes no entry
	 *                  of its own, only nesting for the layers inside it
	 * @param title     the human title, or {@code name} itself when the document
	 *                  carries none
	 * @param depth     nesting among <em>listed</em> layers, zero based: how many
	 *                  named ancestors this layer has, not how many raw
	 *                  {@code <Layer>} elements -- an unnamed grouping layer between
	 *                  two named ones is invisible to this count, since the frontend
	 *                  indents by named parent, and a gap with nothing shown at it
	 *                  would only confuse that
	 * @param queryable whether GetFeatureInfo answers for this layer
	 * @param legendUrl this layer's own GetLegendGraphic address, or null when its
	 *                  document declares none (not inherited: a legend describes one
	 *                  layer's own symbology, never a group's)
	 * @param minScale  the smaller of the two scale denominators (further zoomed in
	 *                  is a larger denominator), or null; inherited from the nearest
	 *                  ancestor that declares one when this layer does not declare its
	 *                  own, per the WMS specification's own inheritance rule
	 * @param maxScale  see {@link #minScale()}
	 * @param bbox      {@code [minLng, minLat, maxLng, maxLat]} in EPSG:4326, or null;
	 *                  inherited the same way as {@link #minScale()}
	 */
	public record Layer(
			String name,
			String title,
			int depth,
			boolean queryable,
			String legendUrl,
			Double minScale,
			Double maxScale,
			double[] bbox) {
	}
}
