import type {
  RasterLayerSpecification,
  RasterSourceSpecification,
  StyleSpecification,
} from 'maplibre-gl'

/**
 * The catalog of selectable background maps. Every entry is a raster basemap that
 * works without an API key -- no account, no token, nothing to configure before the
 * app can draw a map.
 *
 * Two candidates for the light/dark pair were checked and rejected: CARTO's Positron
 * and Dark Matter serve without a key, but their terms require an Enterprise licence
 * for commercial use, and Stamen's tiles moved behind a Stadia Maps key in 2023.
 * Since neither is dependable, "hell" and "dunkel" are rendered from the very same OSM
 * raster tiles through MapLibre's raster paint properties. They are display variants,
 * not separate cartography -- labelled as such in the picker so nobody expects a
 * purpose-built grey or night style.
 *
 * Attribution is rendered as our own element (see `MapCanvas`) instead of MapLibre's
 * default `AttributionControl`, in line with "eigene Bedienelemente statt der
 * MapLibre-Defaults" -- the control is disabled in the Map constructor.
 */
export const BASEMAP_PREFIX = 'basemap:'

export type BasemapId = 'osm' | 'osm-light' | 'osm-dark' | 'opentopo' | 'none'

/**
 * One run of attribution text, optionally a link.
 *
 * The notice is broken into parts rather than kept as one string because the licences
 * ask for a *link* to the project and to the licence, not for their names in prose --
 * "© OpenStreetMap contributors" with nothing to click credits nobody who could be
 * followed up. Concatenating the parts gives back the required wording verbatim (see
 * `attributionText`), so the split costs nothing on the source specification, where
 * MapLibre only takes a string.
 */
export interface AttributionPart {
  text: string
  /** Absolute https URL, or undefined for a run that is only text. */
  href?: string
}

export interface BasemapDefinition {
  id: BasemapId
  /** Shown in the picker. */
  label: string
  /** One line under the label; explains what the entry actually is. */
  hint: string
  /**
   * Exactly the notice the tile provider requires. Empty for "no basemap": claiming
   * OSM for an empty canvas would be attribution for data that is not on screen.
   */
  attribution: readonly AttributionPart[]
  /** Keyed by source id; ids are `BASEMAP_PREFIX`-scoped so a swap can find them again. */
  sources: Record<string, RasterSourceSpecification>
  layers: RasterLayerSpecification[]
}

/** The notice as one string -- for the source specification and for tests. */
export function attributionText(parts: readonly AttributionPart[]): string {
  return parts.map((part) => part.text).join('')
}

const OSM_COPYRIGHT_URL = 'https://www.openstreetmap.org/copyright'

export const OSM_ATTRIBUTION: readonly AttributionPart[] = [
  { text: '© ' },
  { text: 'OpenStreetMap', href: OSM_COPYRIGHT_URL },
  { text: ' contributors' },
]

/** Required verbatim by https://opentopomap.org/about#verwendung (CC-BY-SA). */
const OPENTOPO_ATTRIBUTION: readonly AttributionPart[] = [
  { text: 'Kartendaten: © ' },
  { text: 'OpenStreetMap-Mitwirkende', href: OSM_COPYRIGHT_URL },
  { text: ', SRTM | Kartendarstellung: © ' },
  { text: 'OpenTopoMap', href: 'https://opentopomap.org/' },
  { text: ' (' },
  { text: 'CC-BY-SA', href: 'https://creativecommons.org/licenses/by-sa/3.0/' },
  { text: ')' },
]

const OSM_SOURCE = {
  type: 'raster',
  tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
  tileSize: 256,
  // OSM serves nothing past zoom 19. Without this MapLibre keeps requesting deeper
  // tiles, every one of them fails, and the basemap disappears exactly where editing
  // happens -- with a burst of console errors and no visible reason. Declared, it
  // scales the level-19 tiles up instead.
  maxzoom: 19,
  attribution: attributionText(OSM_ATTRIBUTION),
} as const satisfies RasterSourceSpecification

function osmVariant(
  id: BasemapId,
  label: string,
  hint: string,
  paint: RasterLayerSpecification['paint'],
): BasemapDefinition {
  const key = `${BASEMAP_PREFIX}${id}`
  return {
    id,
    label,
    hint,
    attribution: OSM_ATTRIBUTION,
    // Each variant carries its own source id even though the tile URL is identical:
    // a swap tears the old source down, and sharing one id across entries would mean
    // removing a source that the incoming basemap still needs. The tiles themselves
    // are served from the browser's HTTP cache, so the duplication costs no requests.
    sources: { [key]: { ...OSM_SOURCE } },
    layers: [{ id: key, type: 'raster', source: key, ...(paint ? { paint } : {}) }],
  }
}

export const BASEMAPS: readonly BasemapDefinition[] = [
  osmVariant('osm', 'OpenStreetMap', 'Standardkarte, farbig', undefined),
  osmVariant('osm-light', 'Hell', 'Darstellungsvariante: OSM aufgehellt und entsättigt', {
    'raster-saturation': -0.9,
    'raster-brightness-min': 0.32,
    'raster-contrast': -0.22,
  }),
  osmVariant('osm-dark', 'Dunkel', 'Darstellungsvariante: OSM abgedunkelt', {
    'raster-saturation': -0.65,
    'raster-brightness-max': 0.38,
    'raster-contrast': 0.22,
  }),
  {
    id: 'opentopo',
    label: 'OpenTopoMap',
    hint: 'Topografisch mit Höhenlinien, bis Zoom 17',
    attribution: OPENTOPO_ATTRIBUTION,
    sources: {
      [`${BASEMAP_PREFIX}opentopo`]: {
        type: 'raster',
        // MapLibre has no `{s}` placeholder (that is a Leaflet feature); it round-robins
        // over the list instead, which is how the provider's three subdomains are used.
        tiles: [
          'https://a.tile.opentopomap.org/{z}/{x}/{y}.png',
          'https://b.tile.opentopomap.org/{z}/{x}/{y}.png',
          'https://c.tile.opentopomap.org/{z}/{x}/{y}.png',
        ],
        tileSize: 256,
        maxzoom: 17,
        attribution: attributionText(OPENTOPO_ATTRIBUTION),
      },
    },
    layers: [
      { id: `${BASEMAP_PREFIX}opentopo`, type: 'raster', source: `${BASEMAP_PREFIX}opentopo` },
    ],
  },
  {
    id: 'none',
    label: 'Keine Hintergrundkarte',
    hint: 'Nur die eigenen Layer, ohne Hintergrundkarte',
    attribution: [],
    sources: {},
    layers: [],
  },
]

export const DEFAULT_BASEMAP_ID: BasemapId = 'osm'

/**
 * Anything the catalog does not know falls back to OSM: the value is a free-form
 * string in the database, so a project written by an older build (or by hand) must
 * still open with a working map rather than an empty one.
 */
export function resolveBasemap(id: string | null | undefined): BasemapDefinition {
  return BASEMAPS.find((basemap) => basemap.id === id) ?? BASEMAPS[0]
}

export function resolveBasemapId(id: string | null | undefined): BasemapId {
  return resolveBasemap(id).id
}

/**
 * The id to persist for a pick from the catalog, or null when there is nothing to
 * save. Compared against the stored string rather than the resolved definition on
 * purpose: picking "OpenStreetMap" for a project that carries an unknown id writes
 * the fallback the user is already looking at, instead of leaving the stale value
 * in place forever.
 */
export function basemapChange(stored: string | null | undefined, chosen: string): BasemapId | null {
  const next = resolveBasemapId(chosen)
  return stored === next ? null : next
}

/** True for the sources and layers this module owns, and only those. */
export function isBasemapId(id: string): boolean {
  return id.startsWith(BASEMAP_PREFIX)
}

/**
 * The initial style handed to the `Map` constructor. Later changes go through
 * `applyBasemap` instead, which swaps only the basemap layers and leaves the data
 * layers (and this style's glyph configuration) untouched.
 */
export function buildBasemapStyle(basemapId?: string | null): StyleSpecification {
  const basemap = resolveBasemap(basemapId)
  // Absolute on purpose, same reason as tile URLs in `layerSpecs`: MapLibre may
  // resolve glyph templates without a reliable document base. Relative paths have
  // already bitten us for tiles; glyphs get the same treatment. Self-hosted, so
  // labels keep working for every basemap -- including "none".
  const origin = typeof window === 'undefined' ? '' : window.location.origin
  return {
    version: 8,
    glyphs: `${origin}/api/glyphs/{fontstack}/{range}.pbf`,
    sources: { ...basemap.sources },
    layers: basemap.layers.map((layer) => ({ ...layer })),
  }
}

/** Fallback view when a project has neither a saved position nor any layer extent. */
export const GERMANY_VIEW = {
  center: [10.4515, 51.1657] satisfies [number, number],
  zoom: 5.2,
}
