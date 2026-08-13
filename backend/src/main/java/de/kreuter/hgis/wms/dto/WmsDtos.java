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
	 * One layer of the service, flattened out of the document's nested {@code <Layer>}
	 * tree -- either a real, pickable layer, or an unnamed grouping layer kept only to
	 * explain what its children are (see {@link #name()}).
	 *
	 * @param name      the technical name a GetMap request's {@code LAYERS} parameter
	 *                  uses, or null for an unnamed grouping layer that has children of
	 *                  its own. Such a group is not itself pickable -- there is nothing
	 *                  a request could name -- but it is still listed, as a heading
	 *                  rather than a choice: hiding it entirely made its named children
	 *                  read as if they belonged to whichever named entry happened to sit
	 *                  above them in the flattened list, when their real parent, and the
	 *                  one title that explains them, was the hidden group (orchestrator
	 *                  finding on {@code HH_WMS_Fachdaten_ALKIS}: "Nacht-Schutzzone" and
	 *                  its two siblings read as children of "vorbereitende
	 *                  Untersuchung..." two entries above, when their real, unnamed
	 *                  parent is "Laermschutzbereiche"). An unnamed layer with no
	 *                  children of its own contributes no entry at all -- no name to
	 *                  pick, and no children whose position it would need to explain.
	 * @param title     the human title. {@code name} itself when the document carries
	 *                  none, for a named layer; possibly null for an unnamed group,
	 *                  when even that carries no title of its own
	 * @param depth     nesting depth, zero based, of every {@code <Layer>} boundary
	 *                  crossed from the (unlisted) outermost element -- named or not.
	 *                  An unnamed group's own children are one level deeper than the
	 *                  group itself, same as for a named layer
	 * @param queryable whether GetFeatureInfo answers for this layer; always false for
	 *                  an unnamed group -- nothing can address it to ask
	 * @param legendUrl this layer's own GetLegendGraphic address, or null when its
	 *                  document declares none (not inherited: a legend describes one
	 *                  layer's own symbology, never a group's), and always null for an
	 *                  unnamed group
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
