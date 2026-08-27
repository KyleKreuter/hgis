package de.kreuter.hgis.basemap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The fixed list of basemaps the app knows how to draw, plus the check a client-supplied
 * {@code basemap} value has to pass before it is written.
 *
 * <p>Replaces two things that used to each hold half of this on their own:
 * {@code frontend/src/map/basemap.ts}'s {@code BASEMAPS} array, which is now a thin
 * consumer of {@code GET /api/basemaps} instead, and the enum this class used to be named
 * after ({@code common/Basemap.java}, five entries, added 27.08.), which could check a
 * token but had nowhere to put a URL template, an attribution, a zoom range or a group.
 * Both MCP's {@code list_basemaps} and the picker read the catalog from here now, so a
 * new entry is added in exactly one place.
 *
 * <p>The five original ids -- {@code osm}, {@code osm-light}, {@code osm-dark},
 * {@code opentopo}, {@code none} -- are carried over unchanged. Twelve of the user's
 * existing projects store one of these five as plain text in their {@code basemap}
 * column; renaming any of them would silently break that project's map the next time it
 * opens, with nothing in the response to say why.
 *
 * <p>Not a Spring bean: like the enum it replaces, the list is fixed at compile time and
 * genuinely does not vary at runtime, so a plain static holder is enough -- the same
 * choice {@link de.kreuter.hgis.common.GeometryType} and {@link
 * de.kreuter.hgis.common.FieldType} already made for a fixed set of values.
 */
public final class BasemapCatalog {

	private BasemapCatalog() {
	}

	// -- Groups (VERTRAG.md "GET /api/basemaps"): the exact six strings the contract
	// names for the `group` field. Corrected 27.08. (team lead's own mistake in the
	// first VERTRAG.md revision): "Gelaende" and "Bundeslaender" without their umlaut
	// were never the intended wire value, just an ASCII-only slip in the contract text
	// itself -- the project rule against ae/oe/ue in German text has no exception for a
	// value that lands verbatim in a human's picker. VERTRAG.md now says "Gelände" and
	// "Bundesländer", and mcp already builds against those two spellings.
	public static final String GROUP_STANDARD = "Standard";
	public static final String GROUP_DEUTSCHLAND = "Deutschland";
	public static final String GROUP_LUFT_UND_SATELLITENBILD = "Luft- und Satellitenbild";
	public static final String GROUP_GELAENDE = "Gelände";
	public static final String GROUP_THEMATISCH = "Thematisch";
	/** The Landesdienste {@code recherche} found (see the state entries below). */
	public static final String GROUP_BUNDESLAENDER = "Bundesländer";

	public static final String COVERAGE_WORLD = "world";
	public static final String COVERAGE_EU = "EU";
	public static final String COVERAGE_DE = "DE";

	// -- Bundesland codes for `coverage` (VERTRAG.md, extended 27.08.): ISO 3166-2:DE
	// without the "DE-" prefix. The set the standard itself defines -- sixteen states,
	// no more, no fewer -- not something to derive from whichever Landesdienste
	// `recherche` happens to find first.
	public static final String COVERAGE_BW = "BW";
	public static final String COVERAGE_BY = "BY";
	public static final String COVERAGE_BE = "BE";
	public static final String COVERAGE_BB = "BB";
	public static final String COVERAGE_HB = "HB";
	public static final String COVERAGE_HH = "HH";
	public static final String COVERAGE_HE = "HE";
	public static final String COVERAGE_MV = "MV";
	public static final String COVERAGE_NI = "NI";
	public static final String COVERAGE_NW = "NW";
	public static final String COVERAGE_RP = "RP";
	public static final String COVERAGE_SL = "SL";
	public static final String COVERAGE_SN = "SN";
	public static final String COVERAGE_ST = "ST";
	public static final String COVERAGE_SH = "SH";
	public static final String COVERAGE_TH = "TH";

	private static final String OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright";

	/** Required by every OSM-derived render; identical to {@code OSM_ATTRIBUTION} in {@code basemap.ts}. */
	private static final List<AttributionPart> OSM_ATTRIBUTION = List.of(
			new AttributionPart("© "),
			new AttributionPart("OpenStreetMap", OSM_COPYRIGHT_URL),
			new AttributionPart(" contributors"));

	/** Required verbatim by https://opentopomap.org/about#verwendung (CC-BY-SA). */
	private static final List<AttributionPart> OPENTOPO_ATTRIBUTION = List.of(
			new AttributionPart("Kartendaten: © "),
			new AttributionPart("OpenStreetMap-Mitwirkende", OSM_COPYRIGHT_URL),
			new AttributionPart(", SRTM | Kartendarstellung: © "),
			new AttributionPart("OpenTopoMap", "https://opentopomap.org/"),
			new AttributionPart(" ("),
			new AttributionPart("CC-BY-SA", "https://creativecommons.org/licenses/by-sa/3.0/"),
			new AttributionPart(")"));

	/**
	 * basemap.de's own WMTS capabilities (checked 27.08., {@code
	 * https://sgx.geodatenzentrum.de/wmts_basemapde/1.0.0/WMTSCapabilities.xml},
	 * {@code ows:Fees}) name CC BY 4.0, not dl-de/by-2-0 -- the licence the task briefing
	 * assumed for the whole "amtlich, ohne Schluessel" group. TopPlusOpen's own
	 * capabilities do confirm dl-de/by-2-0 (see {@link #TOPPLUS_ATTRIBUTION}), so this is
	 * a real difference between the two services, not a copy error; reported back rather
	 * than silently matched to the assumption.
	 */
	private static final List<AttributionPart> BASEMAPDE_ATTRIBUTION = List.of(
			new AttributionPart("© "),
			new AttributionPart("GeoBasis-DE / BKG", "https://basemap.de/"),
			new AttributionPart(" (Jahr des letzten Datenbezugs) "),
			new AttributionPart("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/"));

	/** Verbatim Quellenvermerk from TopPlusOpen's own WMTS capabilities ({@code ows:Fees}), checked 27.08. */
	private static final List<AttributionPart> TOPPLUS_ATTRIBUTION = List.of(
			new AttributionPart("Kartendarstellung: © "),
			new AttributionPart("BKG", "https://www.bkg.bund.de/"),
			new AttributionPart(" (Jahr des letzten Datenbezugs) "),
			new AttributionPart("dl-de/by-2-0", "https://www.govdata.de/dl-de/by-2-0"),
			new AttributionPart(", Datenquellen: "),
			new AttributionPart("sgx.geodatenzentrum.de",
					"https://sgx.geodatenzentrum.de/web_public/gdz/datenquellen/datenquellen_topplusopen.html"));

	public static final List<BasemapEntry> ENTRIES = List.of(
			// -- Standard: the original five, unchanged (see class doc). --
			new BasemapEntry("osm", "OpenStreetMap", "Standardkarte, farbig", GROUP_STANDARD,
					"https://tile.openstreetmap.org/{z}/{x}/{y}.png", OSM_ATTRIBUTION,
					0, 19, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("osm-light", "Hell", "Darstellungsvariante: OSM aufgehellt und entsättigt",
					GROUP_STANDARD, "https://tile.openstreetmap.org/{z}/{x}/{y}.png", OSM_ATTRIBUTION,
					0, 19, COVERAGE_WORLD, false, false,
					Map.of("raster-saturation", -0.9, "raster-brightness-min", 0.32, "raster-contrast", -0.22)),
			new BasemapEntry("osm-dark", "Dunkel", "Darstellungsvariante: OSM abgedunkelt",
					GROUP_STANDARD, "https://tile.openstreetmap.org/{z}/{x}/{y}.png", OSM_ATTRIBUTION,
					0, 19, COVERAGE_WORLD, false, false,
					Map.of("raster-saturation", -0.65, "raster-brightness-max", 0.38, "raster-contrast", 0.22)),
			new BasemapEntry("opentopo", "OpenTopoMap", "Topografisch mit Höhenlinien, bis Zoom 17",
					GROUP_GELAENDE, "https://a.tile.opentopomap.org/{z}/{x}/{y}.png", OPENTOPO_ATTRIBUTION,
					0, 17, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("none", "Keine Hintergrundkarte", "Nur die eigenen Layer, ohne Hintergrundkarte",
					GROUP_STANDARD, null, List.of(), 0, 0, COVERAGE_WORLD, false, false, null),

			// -- Deutschland: amtlich, ohne Schluessel (checked 27.08., all 200/image). --
			new BasemapEntry("basemapde-farbe", "basemap.de Farbe", "Amtliche Karte für Deutschland, farbig",
					GROUP_DEUTSCHLAND,
					"https://sgx.geodatenzentrum.de/wmts_basemapde/tile/1.0.0/de_basemapde_web_raster_farbe/default/GLOBAL_WEBMERCATOR/{z}/{y}/{x}.png",
					BASEMAPDE_ATTRIBUTION, 0, 19, COVERAGE_DE, false, false, null),
			new BasemapEntry("basemapde-grau", "basemap.de Grau", "Amtliche Karte für Deutschland, Graustufen",
					GROUP_DEUTSCHLAND,
					"https://sgx.geodatenzentrum.de/wmts_basemapde/tile/1.0.0/de_basemapde_web_raster_grau/default/GLOBAL_WEBMERCATOR/{z}/{y}/{x}.png",
					BASEMAPDE_ATTRIBUTION, 0, 19, COVERAGE_DE, false, false, null),
			new BasemapEntry("topplus", "TopPlusOpen", "Amtliche Topografie, Volltonfarben",
					GROUP_DEUTSCHLAND,
					"https://sgx.geodatenzentrum.de/wmts_topplus_open/tile/1.0.0/web/default/WEBMERCATOR/{z}/{y}/{x}.png",
					TOPPLUS_ATTRIBUTION, 0, 18, COVERAGE_DE, false, false, null),
			new BasemapEntry("topplus-grau", "TopPlusOpen Grau", "Amtliche Topografie, Graustufen",
					GROUP_DEUTSCHLAND,
					"https://sgx.geodatenzentrum.de/wmts_topplus_open/tile/1.0.0/web_grau/default/WEBMERCATOR/{z}/{y}/{x}.png",
					TOPPLUS_ATTRIBUTION, 0, 18, COVERAGE_DE, false, false, null),
			new BasemapEntry("topplus-light", "TopPlusOpen Hell", "Amtliche Topografie, reduzierter Inhalt, dezente Farben",
					GROUP_DEUTSCHLAND,
					"https://sgx.geodatenzentrum.de/wmts_topplus_open/tile/1.0.0/web_light/default/WEBMERCATOR/{z}/{y}/{x}.png",
					TOPPLUS_ATTRIBUTION, 0, 18, COVERAGE_DE, false, false, null),
			new BasemapEntry("topplus-light-grau", "TopPlusOpen Grau Hell",
					"Amtliche Topografie, reduzierter Inhalt, Graustufen", GROUP_DEUTSCHLAND,
					"https://sgx.geodatenzentrum.de/wmts_topplus_open/tile/1.0.0/web_light_grau/default/WEBMERCATOR/{z}/{y}/{x}.png",
					TOPPLUS_ATTRIBUTION, 0, 18, COVERAGE_DE, false, false, null),

			// -- Thematisch: OSM-Familie, ODbL (checked 27.08., all 200/image; attribution per leaflet-providers.js). --
			new BasemapEntry("osm-de", "OpenStreetMap (Deutschland)",
					"Gleiche Kartendarstellung wie OpenStreetMap, andere Kachelquelle", GROUP_STANDARD,
					"https://tile.openstreetmap.de/{z}/{x}/{y}.png", OSM_ATTRIBUTION,
					0, 20, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("osm-hot", "Humanitarian OpenStreetMap",
					"Für Katastrophenhilfe optimiert: hoher Kontrast bei Gebäuden und Straßen", GROUP_THEMATISCH,
					"https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
					List.of(new AttributionPart("© "), new AttributionPart("OpenStreetMap", OSM_COPYRIGHT_URL),
							new AttributionPart(" contributors, Kartendarstellung: "),
							new AttributionPart("Humanitarian OpenStreetMap Team", "https://www.hotosm.org/"),
							new AttributionPart(" bei "),
							new AttributionPart("OpenStreetMap France", "https://openstreetmap.fr/")),
					0, 20, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("cyclosm", "CyclOSM", "Fahrradorientierte Kartendarstellung mit Radwegenetz",
					GROUP_THEMATISCH, "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
					List.of(new AttributionPart("Kartendarstellung: "),
							new AttributionPart("CyclOSM", "https://github.com/cyclosm/cyclosm-cartocss-style"),
							new AttributionPart(", Kartendaten: © "),
							new AttributionPart("OpenStreetMap", OSM_COPYRIGHT_URL),
							new AttributionPart(" contributors")),
					0, 20, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("opnvkarte", "ÖPNV-Karte", "Öffentlicher Nahverkehr weltweit, mit Linien und Haltestellen",
					GROUP_THEMATISCH, "https://tileserver.memomaps.de/tilegen/{z}/{x}/{y}.png",
					List.of(new AttributionPart("Kartendarstellung: "),
							new AttributionPart("memomaps.de", "https://memomaps.de/"),
							new AttributionPart(" ("),
							new AttributionPart("CC-BY-SA", "https://creativecommons.org/licenses/by-sa/2.0/"),
							new AttributionPart("), Kartendaten: © "),
							new AttributionPart("OpenStreetMap", OSM_COPYRIGHT_URL),
							new AttributionPart(" contributors")),
					0, 18, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("openseamap", "OpenSeaMap Seezeichen",
					"Seezeichen als eigenständige Karte -- die Kacheln sind großteils leer, das ist normal",
					GROUP_THEMATISCH, "https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png",
					List.of(new AttributionPart("Kartendaten: © "),
							new AttributionPart("OpenSeaMap", "http://www.openseamap.org"),
							new AttributionPart(" Mitwirkende")),
					0, 18, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("openrailwaymap", "OpenRailwayMap", "Eisenbahninfrastruktur: Strecken, Signale, Bahnhöfe",
					GROUP_THEMATISCH, "https://a.tiles.openrailwaymap.org/standard/{z}/{x}/{y}.png",
					List.of(new AttributionPart("Kartendaten: © "),
							new AttributionPart("OpenStreetMap", OSM_COPYRIGHT_URL),
							new AttributionPart(" contributors, Kartendarstellung: © "),
							new AttributionPart("OpenRailwayMap", "https://www.openrailwaymap.org"),
							new AttributionPart(" ("),
							new AttributionPart("CC-BY-SA", "https://creativecommons.org/licenses/by-sa/3.0/"),
							new AttributionPart(")")),
					0, 19, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("waymarked-hiking", "Waymarked Trails Wandern", "Wanderwege mit Wegmarkierung",
					GROUP_THEMATISCH, "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png",
					waymarkedAttribution(), 0, 18, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("waymarked-cycling", "Waymarked Trails Rad", "Radrouten mit Wegmarkierung",
					GROUP_THEMATISCH, "https://tile.waymarkedtrails.org/cycling/{z}/{x}/{y}.png",
					waymarkedAttribution(), 0, 18, COVERAGE_WORLD, false, false, null),

			// -- Luft- und Satellitenbild: EOX (checked 27.08.; attribution from https://cloudless.eox.at/documentation/license). --
			new BasemapEntry("eox-s2cloudless-2020", "Sentinel-2 Cloudless 2020", "Wolkenfreies Satellitenmosaik, Bildstand 2020",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg",
					eoxCloudlessAttribution(2020), 0, 21, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("eox-s2cloudless-2023", "Sentinel-2 Cloudless 2023", "Wolkenfreies Satellitenmosaik, Bildstand 2023",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2023_3857/default/g/{z}/{y}/{x}.jpg",
					eoxCloudlessAttribution(2023), 0, 21, COVERAGE_WORLD, false, false, null),
			new BasemapEntry("eox-terrain-light", "EOX Terrain Light", "Reliefkarte ohne Beschriftung, hell",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://tiles.maps.eox.at/wmts/1.0.0/terrain-light_3857/default/g/{z}/{y}/{x}.jpg",
					List.of(new AttributionPart("Terrain-Daten: © "),
							new AttributionPart("OpenStreetMap", OSM_COPYRIGHT_URL),
							new AttributionPart(" contributors und andere, Kartendarstellung: © "),
							new AttributionPart("EOX", "https://eox.at")),
					0, 21, COVERAGE_WORLD, false, false, null),

			// -- Esri: neun Dienste, ohne Schluessel und ohne Wasserzeichen, aber ArcGIS-Konto
			// laut Nutzungsbedingungen -- requiresAccount = true, sichtbar, nicht versteckt.
			// Attribution ist copyrightText aus dem MapServer selbst (?f=json, checked 27.08.),
			// nicht erfunden; "(c)" darin durch "©" ersetzt, sonst wörtlich.
			new BasemapEntry("esri-imagery", "Esri World Imagery", "Satelliten- und Luftbild, weltweit",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
					esriAttribution("Esri, Vantor, Earthstar Geographics, and the GIS User Community"),
					0, 23, COVERAGE_WORLD, true, false, null),
			new BasemapEntry("esri-topo", "Esri World Topo Map", "Topografische Weltkarte", GROUP_GELAENDE,
					"https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}",
					esriAttribution(
							"Esri, HERE, Garmin, Intermap, increment P Corp., GEBCO, USGS, FAO, NPS, NRCAN, GeoBase, IGN, Kadaster NL, Ordnance Survey, Esri Japan, METI, Esri China (Hong Kong), © OpenStreetMap contributors, and the GIS User Community"),
					0, 23, COVERAGE_WORLD, true, false, null),
			new BasemapEntry("esri-streets", "Esri World Street Map", "Straßenkarte, weltweit", GROUP_STANDARD,
					"https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
					esriAttribution(
							"Esri, HERE, Garmin, USGS, Intermap, INCREMENT P, NRCan, Esri Japan, METI, Esri China (Hong Kong), Esri Korea, Esri (Thailand), NGCC, © OpenStreetMap contributors, and the GIS User Community"),
					0, 23, COVERAGE_WORLD, true, false, null),
			new BasemapEntry("esri-gray-light", "Esri Light Gray Canvas", "Zurückhaltender heller Hintergrund für eigene Layer",
					GROUP_STANDARD,
					"https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}",
					esriAttribution("Esri, HERE, Garmin, © OpenStreetMap contributors, and the GIS user community"),
					0, 23, COVERAGE_WORLD, true, false, null),
			new BasemapEntry("esri-gray-dark", "Esri Dark Gray Canvas", "Zurückhaltender dunkler Hintergrund für eigene Layer",
					GROUP_STANDARD,
					"https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}",
					esriAttribution("Esri, HERE, Garmin, © OpenStreetMap contributors, and the GIS user community"),
					0, 23, COVERAGE_WORLD, true, false, null),
			new BasemapEntry("esri-ocean", "Esri Ocean Base", "Meeresboden-Topografie und Bathymetrie", GROUP_THEMATISCH,
					"https://server.arcgisonline.com/ArcGIS/rest/services/Ocean/World_Ocean_Base/MapServer/tile/{z}/{y}/{x}",
					esriAttribution("Esri, Garmin, GEBCO, NOAA NGDC, and other contributors"),
					0, 16, COVERAGE_WORLD, true, false, null),
			new BasemapEntry("esri-natgeo", "Esri National Geographic", "Illustrierte Referenzkarte im National-Geographic-Stil",
					GROUP_THEMATISCH,
					"https://server.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/tile/{z}/{y}/{x}",
					esriAttribution(
							"National Geographic, Esri, Garmin, HERE, UNEP-WCMC, USGS, NASA, ESA, METI, NRCAN, GEBCO, NOAA, increment P Corp."),
					0, 16, COVERAGE_WORLD, true, false, null),
			new BasemapEntry("esri-shaded-relief", "Esri Shaded Relief", "Schummerung ohne Beschriftung", GROUP_GELAENDE,
					"https://server.arcgisonline.com/ArcGIS/rest/services/World_Shaded_Relief/MapServer/tile/{z}/{y}/{x}",
					esriAttribution("© 2014 Esri"), 0, 13, COVERAGE_WORLD, true, false, null),
			new BasemapEntry("esri-physical", "Esri Physical Map", "Physische Weltkarte ohne Grenzen", GROUP_GELAENDE,
					"https://server.arcgisonline.com/ArcGIS/rest/services/World_Physical_Map/MapServer/tile/{z}/{y}/{x}",
					esriAttribution("Source: US National Park Service"), 0, 8, COVERAGE_WORLD, true, false, null),

			// -- Bundesländer: Landesdienste, recherchiert von `recherche` (basemap-recherche.md,
			// feature/basemap-recherche, checked between 27.08. and 28.08.) und von mir selbst noch
			// einmal mit echtem Abruf nachgeprueft, gegen eine eigene BBOX je Landeshauptstadt statt
			// der generischen Testkachel -- ein Landesdienst antwortet fuer eine BBOX ausserhalb
			// seines Landes oft leer oder mit Fehler, auch wenn der Dienst selbst funktioniert.
			//
			// Nur Faelle aufgenommen, die entweder ein echtes globales WMTS-Gitter haben (Fall 1,
			// wie basemap.de) oder nur als WMS existieren (Fall 3, {bbox-epsg-3857}). Dienste mit
			// eigenem, nicht-globalem Kachelgitter (Fall 2: Brandenburg- und Sachsen-DOP als WMTS,
			// NRW- und Mecklenburg-Vorpommern-DOP mit fester Zoomverschiebung) brauchen Umrechnung
			// statt Textersetzung und passen in kein urlTemplate -- fuer alle vier gibt es hier aber
			// den WMS-Zwilling desselben Datensatzes, der ohne Umrechnung auskommt.
			//
			// Zwei Dienste bewusst NICHT aufgenommen:
			// - Baden-Wuerttemberg (Basiskarte, LGL): die einzige Capabilities-URL, die recherche
			//   fand, funktionierte nur mit user=/password=-Parametern aus einem oeffentlich
			//   indexierten Treffer, nicht von einer offiziellen "so nutzen Sie den Dienst"-Seite.
			//   Fest einprogrammierte Zugangsdaten eines Dritten in einem oeffentlichen Katalog
			//   waeren genau das Risiko, das BasemapUrlTemplate fuer Freitext-Eintraege ablehnt --
			//   fuer einen von uns selbst geschriebenen Katalogeintrag gilt dieselbe Vorsicht umso
			//   mehr. recherche selbst empfiehlt, das nicht ungeprueft zu uebernehmen.
			// - Sachsen-Anhalt (DOP20 OpenData): keine Fees/AccessConstraints im Capabilities-
			//   Dokument gefunden, recherche empfiehlt ausdruecklich, den Lizenztext beim LVermGeo
			//   zu erfragen statt eine Formulierung zu raten. Ohne verifizierten Lizenzstatus kein
			//   Katalogeintrag -- dieselbe Zurueckhaltung wie beim Verzicht auf CARTO.
			new BasemapEntry("hh-geobasiskarten-farbig", "Geobasiskarten Hamburg (farbig)",
					"Amtliche Stadtkarte, farbig", GROUP_BUNDESLAENDER,
					"https://geodienste.hamburg.de/HH_WMS_Geobasiskarten?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=geobasiskarten_farbig&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					hhAttribution("Freie und Hansestadt Hamburg, Landesbetrieb Geoinformation und Vermessung"),
					10, 19, COVERAGE_HH, false, false, null),
			new BasemapEntry("hh-dop-unbelaubt", "Luftbild Hamburg (unbelaubt)",
					"Digitales Orthophoto, Winteraufnahme ohne Laub", GROUP_LUFT_UND_SATELLITENBILD,
					"https://geodienste.hamburg.de/wms_dop_zeitreihe_unbelaubt?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=dop_zeitreihe_unbelaubt&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					hhAttribution("Freie und Hansestadt Hamburg, Landesbetrieb Geoinformation und Vermessung (LGV)"),
					0, 20, COVERAGE_HH, false, false, null),
			new BasemapEntry("hh-dop-belaubt", "Luftbild Hamburg (belaubt)",
					"Digitales Orthophoto, Sommeraufnahme mit Laub -- einzelne Luecken-Kacheln koennen "
							+ "abweichend lizenziert sein (Maxar)", GROUP_LUFT_UND_SATELLITENBILD,
					"https://geodienste.hamburg.de/wms_dop_zeitreihe_belaubt?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=dop_zeitreihe_belaubt&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					hhAttribution("Freie und Hansestadt Hamburg, Landesbetrieb Geoinformation und Vermessung (LGV)"),
					0, 20, COVERAGE_HH, false, false, null),
			new BasemapEntry("bb-dop20c", "Luftbild Brandenburg/Berlin (DOP20)",
					"Digitales Orthophoto, 20 cm Bodenauflösung", GROUP_LUFT_UND_SATELLITENBILD,
					"https://isk.geobasis-bb.de/mapproxy/dop20c/service/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=bebb_dop20c&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					bbAttribution(), 0, 22, COVERAGE_BB, false, false, null),
			new BasemapEntry("bb-webatlasde-halbton", "WebAtlasDE Brandenburg/Berlin (Halbton)",
					"Amtliche Übersichtskarte", GROUP_BUNDESLAENDER,
					"https://isk.geobasis-bb.de/mapproxy/webatlasde_2024/service/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=WebAtlasDE_BEBB_halbton&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					bbAttribution(), 0, 19, COVERAGE_BB, false, false, null),
			new BasemapEntry("nw-dop", "Luftbild Nordrhein-Westfalen (DOP)", "Digitales Orthophoto, 10 cm Bodenauflösung",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://www.wms.nrw.de/geobasis/wms_nw_dop?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=nw_dop_rgb&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					List.of(new AttributionPart("Land Nordrhein-Westfalen"), new AttributionPart(" – "),
							new AttributionPart("dl-de/zero-2-0", "https://www.govdata.de/dl-de/zero-2-0"),
							new AttributionPart(" (keine Namensnennungspflicht)")),
					5, 21, COVERAGE_NW, false, false, null),
			new BasemapEntry("by-webkarte", "Amtliche Karte Bayern (farbig)", "Amtliche Landeskarte, farbig",
					GROUP_BUNDESLAENDER, "https://wmtsod1.bayernwolke.de/wmts/by_webkarte/smerc/{z}/{x}/{y}",
					byAttribution(), 0, 19, COVERAGE_BY, false, false, null),
			new BasemapEntry("by-webkarte-grau", "Amtliche Karte Bayern (grau)", "Amtliche Landeskarte, Graustufen",
					GROUP_BUNDESLAENDER, "https://wmtsod1.bayernwolke.de/wmts/by_webkarte_grau/smerc/{z}/{x}/{y}",
					byAttribution(), 0, 19, COVERAGE_BY, false, false, null),
			new BasemapEntry("by-dop", "Luftbild Bayern (DOP)", "Digitales Orthophoto, echtfarbig",
					GROUP_LUFT_UND_SATELLITENBILD, "https://wmtsod1.bayernwolke.de/wmts/by_dop/smerc/{z}/{x}/{y}",
					byAttribution(), 0, 19, COVERAGE_BY, false, false, null),
			new BasemapEntry("by-dop-cir", "Luftbild Bayern (DOP, Color-Infrarot)",
					"Digitales Orthophoto, Falschfarben für Vegetation", GROUP_LUFT_UND_SATELLITENBILD,
					"https://wmtsod1.bayernwolke.de/wmts/by_dop_cir/smerc/{z}/{x}/{y}",
					byAttribution(), 0, 19, COVERAGE_BY, false, false, null),
			new BasemapEntry("be-truedop", "Luftbild Berlin (TrueDOP 2024)", "Digitales Orthophoto, 20 cm Bodenauflösung",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://gdi.berlin.de/services/wms/truedop_2024?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=truedop_2024&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					List.of(new AttributionPart("Geoportal Berlin"), new AttributionPart(" – "),
							new AttributionPart("dl-de/zero-2-0", "https://www.govdata.de/dl-de/zero-2-0"),
							new AttributionPart(" – „Es gelten keine Zugriffsbeschränkungen.“")),
					0, 19, COVERAGE_BE, false, false, null),
			new BasemapEntry("ni-dop20", "Luftbild Niedersachsen (DOP20)", "Digitales Orthophoto, 20 cm Bodenauflösung",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=ni_dop20&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					List.of(new AttributionPart("LGLN (2024) "),
							new AttributionPart("Creative Commons Namensnennung – 4.0 International (CC BY 4.0)",
									"https://creativecommons.org/licenses/by/4.0/")),
					0, 20, COVERAGE_NI, false, false, null),
			new BasemapEntry("sh-dop20", "Luftbild Schleswig-Holstein (DOP20)", "Digitales Orthophoto, 20 cm Bodenauflösung",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://dienste.gdi-sh.de/WMS_SH_DOP20col_OpenGBD?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=sh_dop20_rgb&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					List.of(new AttributionPart("© GeoBasis-DE/LVermGeo SH/"),
							new AttributionPart("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")),
					0, 20, COVERAGE_SH, false, false, null),
			new BasemapEntry("sn-dop", "Luftbild Sachsen (DOP)", "Digitales Orthophoto, echtfarbig",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://geodienste.sachsen.de/wms_geosn_dop-rgb/guest?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=sn_dop_020&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					List.of(new AttributionPart("Geobasisinformation und Vermessung Sachsen (GeoSN)"),
							new AttributionPart(" – Nutzungsbedingungen: "),
							new AttributionPart("geoportal.sachsen.de", "https://geoportal.sachsen.de/nutzungsbedingungen.html")),
					0, 20, COVERAGE_SN, false, false, null),
			new BasemapEntry("th-dop", "Luftbild Thüringen (DOP)", "Digitales Orthophoto, echtfarbig",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://www.geoproxy.geoportal-th.de/geoproxy/services/DOP?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=th_dop&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					List.of(new AttributionPart("Land Thüringen ("),
							new AttributionPart("dl-de/by-2-0", "https://www.govdata.de/dl-de/by-2-0"),
							new AttributionPart(") – „NONE. Es gelten keine Beschränkungen.“")),
					0, 19, COVERAGE_TH, false, false, null),
			new BasemapEntry("hb-dop20", "Luftbild Bremen (DOP20 2023)", "Digitales Orthophoto, 20 cm Bodenauflösung",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://geodienste.bremen.de/wms_dop20_2023?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=DOP20_2023_HB&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					List.of(new AttributionPart("© Landesamt GeoInformation Bremen ("),
							new AttributionPart("CC-BY", "https://creativecommons.org/licenses/by/"),
							new AttributionPart(")")),
					0, 20, COVERAGE_HB, false, false, null),
			new BasemapEntry("he-dop", "Luftbild Hessen (DOP)", "Digitales Orthophoto, echtfarbig",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://www.gds-srv.hessen.de/cgi-bin/lika-services/de-viewer/access/ogc-free-images.ows?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=he_dop_rgb&STYLES=&SRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					List.of(new AttributionPart("Hessische Verwaltung für Bodenmanagement und Geoinformation (HVBG)"),
							new AttributionPart(" – § 18 HVGG: „Jede Nutzung der Geobasisdaten und zugehörigen "
									+ "Metadaten ist ohne Einschränkung oder Bedingung erlaubt.“")),
					0, 19, COVERAGE_HE, false, false, null),
			new BasemapEntry("mv-dop", "Luftbild Mecklenburg-Vorpommern (DOP)", "Digitales Orthophoto",
					GROUP_LUFT_UND_SATELLITENBILD,
					"https://www.geodaten-mv.de/dienste/adv_dop?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=mv_dop&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png",
					List.of(new AttributionPart("© GeoBasis-DE/M-V <Jahr der letzten Datenlieferung>")),
					0, 19, COVERAGE_MV, false, false, null));

	private static List<AttributionPart> waymarkedAttribution() {
		return List.of(new AttributionPart("Kartendaten: © "),
				new AttributionPart("OpenStreetMap", OSM_COPYRIGHT_URL),
				new AttributionPart(" contributors, Kartendarstellung: © "),
				new AttributionPart("waymarkedtrails.org", "https://waymarkedtrails.org"),
				new AttributionPart(" ("),
				new AttributionPart("CC-BY-SA", "https://creativecommons.org/licenses/by-sa/3.0/"),
				new AttributionPart(")"));
	}

	/** CC BY-NC-SA 4.0, per https://cloudless.eox.at/documentation/license (checked 27.08.). */
	private static List<AttributionPart> eoxCloudlessAttribution(int year) {
		return List.of(new AttributionPart("Sentinel-2 cloudless " + year + " by "),
				new AttributionPart("EOX IT Services GmbH", "https://cloudless.eox.at"),
				new AttributionPart(" (enthält veränderte Copernicus-Sentinel-Daten " + year + "), "),
				new AttributionPart("CC BY-NC-SA 4.0", "https://creativecommons.org/licenses/by-nc-sa/4.0/"));
	}

	private static List<AttributionPart> esriAttribution(String copyrightText) {
		return List.of(new AttributionPart("© " + copyrightText));
	}

	/** Verbatim Quellenvermerk from Hamburg's own WMS capabilities ({@code ows:Fees}), per basemap-recherche.md. */
	private static List<AttributionPart> hhAttribution(String quellenvermerk) {
		return List.of(new AttributionPart("© " + quellenvermerk + " ("),
				new AttributionPart("dl-de/by-2-0", "https://www.govdata.de/dl-de/by-2-0"),
				new AttributionPart(")"));
	}

	/**
	 * Verbatim Quellenvermerk from Brandenburg's own WMS capabilities
	 * ({@code ows:AccessConstraints}), per basemap-recherche.md: "© GeoBasis-DE/LGB,
	 * dl-de/by-2-0, (Daten geändert)" -- both entries also cover Berlin-adjacent data, per
	 * the same source, which adds "© Geoportal Berlin, dl-de/by-2-0" for that case; not
	 * repeated here since neither entry is Berlin-specific the way {@code be-truedop} is.
	 */
	private static List<AttributionPart> bbAttribution() {
		return List.of(new AttributionPart("© GeoBasis-DE/LGB, "),
				new AttributionPart("dl-de/by-2-0", "https://www.govdata.de/dl-de/by-2-0"),
				new AttributionPart(" (Daten geändert)"));
	}

	/**
	 * Bavaria's own Quellenvermerk wording exists only in a PDF (basemap-recherche.md,
	 * team lead's instruction 27.08.: link to it rather than guess its wording) -- not
	 * quoted here for that reason, unlike every other attribution in this class.
	 */
	private static List<AttributionPart> byAttribution() {
		return List.of(new AttributionPart("Bayerische Vermessungsverwaltung"),
				new AttributionPart(" – Quellenvermerk siehe "),
				new AttributionPart("Nutzungshinweise (PDF)", "https://geodaten.bayern.de/odd/m/3/pdf/WMTS_Nutzungshinweise.pdf"));
	}

	private static final Map<String, BasemapEntry> BY_ID = ENTRIES.stream()
			.collect(Collectors.toMap(BasemapEntry::id, entry -> entry, (a, b) -> a, LinkedHashMap::new));

	/** The catalog, in the fixed order {@link #ENTRIES} declares it. */
	public static List<BasemapEntry> list() {
		return ENTRIES;
	}

	public static boolean isKnownId(String id) {
		return BY_ID.containsKey(id);
	}

	/**
	 * Validates a client-supplied {@code basemap} value before it is written -- either a
	 * catalog id, or (VERTRAG.md "Setzen: die bestehenden Endpunkte") free-text starting
	 * with {@code https://}, which {@link BasemapUrlTemplate} checks instead.
	 *
	 * @throws IllegalArgumentException with {@link #unknownValueMessage} when neither applies
	 */
	public static void requireValid(String value) {
		if (BasemapUrlTemplate.isUrlTemplate(value)) {
			BasemapUrlTemplate.requireValid(value);
			return;
		}
		if (!isKnownId(value)) {
			throw new IllegalArgumentException(unknownValueMessage(value));
		}
	}

	/**
	 * The message for a value that is neither a catalog id nor an https tile-URL template
	 * -- always names the valid ids, the same {@code *.unknownTypeMessage(raw)} pattern
	 * {@link de.kreuter.hgis.common.GeometryType} already follows.
	 */
	public static String unknownValueMessage(String raw) {
		return "Unbekannte Hintergrundkarte: " + raw + ". Gültig sind " + joinedIds()
				+ ", oder eine URL-Vorlage, die mit https:// beginnt und entweder {z}, {x} und {y} "
				+ "oder {bbox-epsg-3857} enthält.";
	}

	private static String joinedIds() {
		return ENTRIES.stream().map(BasemapEntry::id).collect(Collectors.joining(", "));
	}
}
