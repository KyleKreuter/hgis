package de.kreuter.hgis.basemap;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Validates a client-supplied {@code basemap} value that is not a catalog id, but a
 * free-text XYZ tile-URL template a project or layer points MapLibre at directly
 * (VERTRAG.md "Setzen: die bestehenden Endpunkte") -- {@link
 * BasemapCatalog#requireValid} sends anything starting with {@code https://} here.
 *
 * <p>Deliberately not {@link de.kreuter.hgis.wms.WmsUrlGuard}, even though both guard a
 * client-supplied URL: that guard closes an SSRF hole, because {@code
 * GET /api/wms/capabilities?url=...} makes <em>this server</em> fetch whatever address a
 * client names -- the address is resolved and checked against private/loopback/link-local
 * ranges before every request, including redirects. A tile-URL template is never fetched
 * by the backend at all; the browser of whoever has the project open loads the tiles
 * directly from wherever the URL points, exactly like it already does for the fixed
 * catalog entries. There is no server-side request to forge here, so the address-range
 * checks that guard exists for do not apply.
 *
 * <p>What actually matters once the value is stored is that every other browser that ever
 * opens the project will load it verbatim, unattended:
 *
 * <ul>
 *   <li>Only {@code https://} -- checked by the caller before this class is even reached.
 *       A plain {@code http://} tile source would make every viewer's browser send a
 *       mixed-content request from a page the app itself serves over https (browsers
 *       block or warn on exactly that); {@code javascript:} and {@code data:} are refused
 *       the same way, simply by never matching the {@code https://} prefix and falling
 *       through to {@link BasemapCatalog#unknownValueMessage}, which also spells out the
 *       prefix rule for whoever typed the wrong scheme.
 *   <li>Either {@code {z}}, {@code {x}} and {@code {y}} must all appear (a tile-raster
 *       template, XYZ or WMTS), or {@code {bbox-epsg-3857}} must (a WMS-GetMap template --
 *       VERTRAG.md "Zwei Formen von urlTemplate", added 27.08. once the Landesdienste
 *       research showed most German states offer WMS, not WMTS; Hamburg's aerial imagery
 *       only exists as WMS). Both forms are ordinary raster sources to MapLibre, which
 *       substitutes either placeholder set in the same chain
 *       ({@code maplibre-gl-shared.mjs}, {@code .replace(/{bbox-epsg-3857}/g, ...)} right
 *       next to {@code {z}}/{@code {x}}/{@code {y}}) -- nothing here or in the frontend
 *       needs to know which form a given entry uses. A template with neither is rejected:
 *       it would return the same image for every tile. A template with both is let
 *       through -- pointless, but nobody is hurt by it, and one fewer rule is one fewer
 *       rule.
 *   <li>No userinfo ({@code user:pass@host}) -- unlike a WMS URL, which lives in a
 *       server-side config the backend alone ever reads, a project's {@code basemap} is
 *       returned verbatim to every client with access to that project. Credentials
 *       embedded here would leak to everyone who can open the project, not just to
 *       whoever typed them in.
 *   <li>A length cap, the same reasoning {@code ProjectDtos.CreateRequest#description}
 *       already applies to free text: no genuine tile URL needs more than this (the
 *       longest one in {@link BasemapCatalog} is well under 200 characters), and without
 *       a bound the column would accept an unbounded string forever.
 * </ul>
 */
final class BasemapUrlTemplate {

	static final String PREFIX = "https://";

	private static final String BBOX_PLACEHOLDER = "{bbox-epsg-3857}";

	/** Matches {@code ProjectDtos.CreateRequest#description}'s bound for free text -- see the class doc. */
	private static final int MAX_LENGTH = 2000;

	private BasemapUrlTemplate() {
	}

	static boolean isUrlTemplate(String value) {
		return value != null && value.startsWith(PREFIX);
	}

	/**
	 * @throws IllegalArgumentException explaining which rule failed
	 */
	static void requireValid(String value) {
		if (value.length() > MAX_LENGTH) {
			throw new IllegalArgumentException(
					"Die URL-Vorlage darf höchstens " + MAX_LENGTH + " Zeichen lang sein.");
		}
		boolean hasTileTriple = value.contains("{z}") && value.contains("{x}") && value.contains("{y}");
		boolean hasBbox = value.contains(BBOX_PLACEHOLDER);
		if (!hasTileTriple && !hasBbox) {
			throw new IllegalArgumentException(
					"Die URL-Vorlage muss entweder {z}, {x} und {y} oder {bbox-epsg-3857} enthalten.");
		}

		// Placeholders substituted with a digit before parsing: '{' and '}' are not legal
		// URI characters (RFC 2396, which java.net.URI enforces), so parsing the raw
		// template would fail for every genuine template, not just malformed ones.
		URI probe;
		try {
			probe = new URI(value.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")
					.replace(BBOX_PLACEHOLDER, "0"));
		}
		catch (URISyntaxException e) {
			throw new IllegalArgumentException("Die URL-Vorlage ist keine gültige Adresse.");
		}
		if (probe.getHost() == null || probe.getHost().isBlank()) {
			throw new IllegalArgumentException("Die URL-Vorlage muss einen Hostnamen enthalten.");
		}
		if (probe.getUserInfo() != null) {
			throw new IllegalArgumentException("Die URL-Vorlage darf keine Zugangsdaten enthalten.");
		}
	}
}
