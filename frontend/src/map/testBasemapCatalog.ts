import type { BasemapCatalogEntry } from '@/api/basemaps'
import { OSM_ATTRIBUTION } from './basemap'

/**
 * A stand-in for `GET /api/basemaps`, shared by every test that used to import the old
 * static `BASEMAPS` list. Carries the five ids that predate the catalog (`osm`,
 * `osm-light`, `osm-dark`, `opentopo`, `none`) with the exact tile URLs, paint and
 * attribution the static list used to hardcode, plus a handful of catalog-only entries
 * that exercise grouping, `requiresAccount`, `deprecated` and `coverage` -- none of
 * which the old five-entry list could ever cover.
 */

const OPENTOPO_ATTRIBUTION: BasemapCatalogEntry['attribution'] = [
  { text: 'Kartendaten: © ' },
  { text: 'OpenStreetMap-Mitwirkende', href: 'https://www.openstreetmap.org/copyright' },
  { text: ', SRTM | Kartendarstellung: © ' },
  { text: 'OpenTopoMap', href: 'https://opentopomap.org/' },
  { text: ' (' },
  { text: 'CC-BY-SA', href: 'https://creativecommons.org/licenses/by-sa/3.0/' },
  { text: ')' },
]

export const TEST_BASEMAP_CATALOG: BasemapCatalogEntry[] = [
  {
    id: 'osm',
    title: 'OpenStreetMap',
    hint: 'Standardkarte, farbig',
    group: 'Standard',
    urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: OSM_ATTRIBUTION,
    minZoom: 0,
    maxZoom: 19,
    coverage: 'world',
    requiresAccount: false,
    deprecated: false,
    paint: null,
  },
  {
    id: 'osm-light',
    title: 'Hell',
    hint: 'Darstellungsvariante: OSM aufgehellt und entsättigt',
    group: 'Standard',
    urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: OSM_ATTRIBUTION,
    minZoom: 0,
    maxZoom: 19,
    coverage: 'world',
    requiresAccount: false,
    deprecated: false,
    paint: {
      'raster-saturation': -0.9,
      'raster-brightness-min': 0.32,
      'raster-contrast': -0.22,
    },
  },
  {
    id: 'osm-dark',
    title: 'Dunkel',
    hint: 'Darstellungsvariante: OSM abgedunkelt',
    group: 'Standard',
    urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: OSM_ATTRIBUTION,
    minZoom: 0,
    maxZoom: 19,
    coverage: 'world',
    requiresAccount: false,
    deprecated: false,
    paint: {
      'raster-saturation': -0.65,
      'raster-brightness-max': 0.38,
      'raster-contrast': 0.22,
    },
  },
  {
    id: 'opentopo',
    title: 'OpenTopoMap',
    hint: 'Topografisch mit Höhenlinien, bis Zoom 17',
    group: 'Gelände',
    urlTemplate: 'https://a.tile.opentopomap.org/{z}/{x}/{y}.png',
    attribution: OPENTOPO_ATTRIBUTION,
    minZoom: 0,
    maxZoom: 17,
    coverage: 'world',
    requiresAccount: false,
    deprecated: false,
    paint: null,
  },
  {
    id: 'none',
    title: 'Keine Hintergrundkarte',
    hint: 'Nur die eigenen Layer, ohne Hintergrundkarte',
    group: 'Standard',
    urlTemplate: null,
    attribution: [],
    minZoom: 0,
    maxZoom: 0,
    coverage: 'world',
    requiresAccount: false,
    deprecated: false,
    paint: null,
  },
  {
    id: 'basemapde-grau',
    title: 'basemap.de Grau',
    hint: 'Amtliche Karte für Deutschland, Graustufen',
    group: 'Deutschland',
    urlTemplate:
      'https://sgx.geodatenzentrum.de/wmts_basemapde/tile/1.0.0/de_basemapde_web_raster_grau/default/GLOBAL_WEBMERCATOR/{z}/{y}/{x}.png',
    attribution: [
      { text: '© ' },
      { text: 'GeoBasis-DE / BKG', href: 'https://basemap.de/' },
      { text: ' (dl-de/by-2-0)', href: 'https://www.govdata.de/dl-de/by-2-0' },
    ],
    minZoom: 0,
    maxZoom: 18,
    coverage: 'DE',
    requiresAccount: false,
    deprecated: false,
    paint: null,
  },
  {
    id: 'esri-imagery',
    title: 'Esri World Imagery',
    hint: 'Satellitenbild, weltweit',
    group: 'Luft- und Satellitenbild',
    urlTemplate:
      'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attribution: [{ text: '© Esri' }],
    minZoom: 0,
    maxZoom: 19,
    coverage: 'world',
    requiresAccount: true,
    deprecated: false,
    paint: null,
  },
  {
    id: 'stamen-toner-relaunch',
    title: 'Stamen Toner (früher)',
    hint: 'Vom Anbieter abgekündigter Dienst, nur zur Übergangszeit noch erreichbar',
    group: 'Thematisch',
    urlTemplate: 'https://tiles.example.test/toner/{z}/{x}/{y}.png',
    attribution: [{ text: '© Stamen Design' }],
    minZoom: 0,
    maxZoom: 18,
    coverage: 'world',
    requiresAccount: false,
    deprecated: true,
    paint: null,
  },
  {
    id: 'geobasis-by-dop',
    title: 'Digitale Orthophotos Bayern',
    hint: 'Luftbild, nur Bayern',
    group: 'Bundesländer',
    // Form B (WMS-GetMap) from VERTRAG.md "Zwei Formen von urlTemplate" -- most Land
    // survey offices, Bavaria's among them, serve only WMS, no WMTS at all.
    urlTemplate:
      'https://geoservices.bayern.de/od/wms/dop/v1/dop20?SERVICE=WMS&REQUEST=GetMap&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256&FORMAT=image/png',
    attribution: [{ text: '© Bayerische Vermessungsverwaltung' }],
    minZoom: 0,
    maxZoom: 20,
    coverage: 'BY',
    requiresAccount: false,
    deprecated: false,
    paint: null,
  },
]
