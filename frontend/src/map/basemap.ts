import type {
  RasterLayerSpecification,
  RasterSourceSpecification,
  StyleSpecification,
} from 'maplibre-gl'
import type { BasemapCatalogEntry } from '@/api/basemaps'

/**
 * Turns the catalog (`GET /api/basemaps`, fetched once via `useBasemaps` and never
 * refetched -- VERTRAG.md) into the MapLibre sources and layers the map actually draws.
 *
 * The catalog itself lives on the server now; this module only resolves an id (or a
 * free-text tile URL, see `isCustomBasemapUrl`) against it and builds the raster
 * source/layer pair MapLibre needs. "Hell" and "Dunkel" are still the same OSM raster
 * tiles as "OpenStreetMap" -- the paint that turns one into the other now travels in the
 * catalog's own `paint` field instead of being hardcoded here.
 *
 * Attribution is rendered as our own element (see `MapCanvas`) instead of MapLibre's
 * default `AttributionControl`, in line with "eigene Bedienelemente statt der
 * MapLibre-Defaults" -- the control is disabled in the Map constructor.
 */
export const BASEMAP_PREFIX = 'basemap:'

/**
 * One run of attribution text, optionally a link.
 *
 * The notice is broken into parts rather than kept as one string because the licences
 * ask for a *link* to the project and to the licence, not for their names in prose --
 * "© OpenStreetMap contributors" with nothing to click credits nobody who could be
 * followed up. Concatenating the parts gives back the required wording verbatim (see
 * `attributionText`).
 */
export interface AttributionPart {
  text: string
  /** Absolute https URL, or undefined for a run that is only text. */
  href?: string
}

export interface BasemapDefinition {
  id: string
  /** Shown in the picker. */
  label: string
  /** One line under the label; explains what the entry actually is. */
  hint: string
  /**
   * Exactly the notice the tile provider requires. Empty for "no basemap" and for a
   * free-text URL: claiming a licence nobody in the catalog vouched for would credit
   * data that was never checked.
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

export const DEFAULT_BASEMAP_ID = 'osm'

/**
 * A stored value is either a catalog id or a whole tile URL template (VERTRAG.md
 * "Setzen"): a value starting with `https://` is free text, everything else is looked
 * up in the catalog. Mirrors the same rule the backend applies before it writes the
 * column.
 */
export function isCustomBasemapUrl(value: string | null | undefined): boolean {
  return value != null && value.startsWith('https://')
}

/**
 * Checks a free-text tile URL a picker's own input collects, before it is ever sent to
 * the server (CONTRACT.md "Freitext"). The server checks the same shape again on save --
 * this only spares the round trip needed to find out something is missing. `null` means
 * the value is fine.
 *
 * Accepts either of `urlTemplate`'s two forms (VERTRAG.md "Zwei Formen von
 * urlTemplate"): the usual `{z}`/`{x}`/`{y}` tile triple, or `{bbox-epsg-3857}` alone
 * for a WMS-GetMap URL -- most German Land survey offices serve only the latter (no
 * WMTS at all), the Hamburg aerial imagery among them. MapLibre replaces both
 * placeholder kinds in the same pass, so nothing downstream needs to know which form a
 * given URL used. `{bbox-epsg-3857}` is not a new idea in this codebase: it is the
 * exact token `map/wmsTiles.ts`'s `buildWmsGetMapUrl` already bakes into a Kartenbild
 * layer's own GetMap address, and `syncLayers.ts` already treats the result as a plain
 * raster source -- a catalog or free-text basemap in this form needs no special case
 * either.
 */
export function validateBasemapUrlTemplate(value: string): string | null {
  const trimmed = value.trim()
  if (trimmed.length === 0) return 'Eine Kachel-URL ist erforderlich.'
  if (!trimmed.startsWith('https://')) return 'Die URL muss mit https:// beginnen.'
  if (trimmed.includes('{bbox-epsg-3857}')) return null

  const missing = (['{z}', '{x}', '{y}'] as const).filter((placeholder) => !trimmed.includes(placeholder))
  if (missing.length === 0) return null
  if (missing.length === 3) {
    return (
      'Die URL braucht entweder die Platzhalter {z}, {x} und {y} für ein Kachelraster, ' +
      'oder {bbox-epsg-3857} für eine WMS-GetMap-Adresse.'
    )
  }
  return `Der Platzhalter ${missing[0]} fehlt in der URL.`
}

function sourceId(id: string): string {
  return `${BASEMAP_PREFIX}${id}`
}

function toBasemapDefinition(entry: BasemapCatalogEntry): BasemapDefinition {
  if (!entry.urlTemplate) {
    // "none", and any future basemap-less entry: nothing to draw, nothing to credit.
    return {
      id: entry.id,
      label: entry.title,
      hint: entry.hint,
      attribution: [],
      sources: {},
      layers: [],
    }
  }

  const key = sourceId(entry.id)
  const source: RasterSourceSpecification = {
    type: 'raster',
    tiles: [entry.urlTemplate],
    tileSize: 256,
    minzoom: entry.minZoom,
    // Caps requests at the deepest zoom the service actually serves -- without it
    // MapLibre keeps requesting deeper tiles past that point, every one fails, and the
    // basemap disappears exactly where editing happens.
    maxzoom: entry.maxZoom,
    attribution: attributionText(entry.attribution),
  }
  return {
    id: entry.id,
    label: entry.title,
    hint: entry.hint,
    attribution: entry.attribution,
    sources: { [key]: source },
    layers: [{ id: key, type: 'raster', source: key, ...(entry.paint ? { paint: entry.paint } : {}) }],
  }
}

/**
 * A free-text tile URL, turned into the same shape a catalog entry produces -- so
 * `applyBasemap`/`buildBasemapStyle` need not know the difference. Carries no
 * attribution and no zoom limits: nothing in the catalog vouches for a URL nobody
 * curated, so nothing here can name a licence or a maximum zoom for it either.
 */
function customBasemapDefinition(urlTemplate: string): BasemapDefinition {
  const key = sourceId(urlTemplate)
  return {
    id: urlTemplate,
    label: 'Eigene Kachel-URL',
    hint: urlTemplate,
    attribution: [],
    sources: { [key]: { type: 'raster', tiles: [urlTemplate], tileSize: 256, attribution: '' } },
    layers: [{ id: key, type: 'raster', source: key }],
  }
}

/** Used only when the catalog itself is empty -- defensive, never hit once the route
 * loader has actually prefetched `GET /api/basemaps`. */
const EMPTY_CATALOG_FALLBACK: BasemapDefinition = {
  id: DEFAULT_BASEMAP_ID,
  label: 'OpenStreetMap',
  hint: '',
  attribution: OSM_ATTRIBUTION,
  sources: {},
  layers: [],
}

/**
 * Anything the catalog does not know falls back to OSM: the value is a free-form string
 * in the database, so a project written by an older build (or by hand) must still open
 * with a working map rather than an empty one.
 */
export function resolveBasemap(
  catalog: readonly BasemapCatalogEntry[],
  id: string | null | undefined,
): BasemapDefinition {
  if (isCustomBasemapUrl(id)) return customBasemapDefinition(id!)
  const entry =
    catalog.find((candidate) => candidate.id === id) ??
    catalog.find((candidate) => candidate.id === DEFAULT_BASEMAP_ID) ??
    catalog[0]
  return entry ? toBasemapDefinition(entry) : EMPTY_CATALOG_FALLBACK
}

export function resolveBasemapId(
  catalog: readonly BasemapCatalogEntry[],
  id: string | null | undefined,
): string {
  return resolveBasemap(catalog, id).id
}

/**
 * The id to persist for a pick from the catalog (or a free-text URL), or null when there
 * is nothing to save. Compared against the stored string rather than the resolved
 * definition on purpose: picking "OpenStreetMap" for a project that carries an unknown
 * id writes the fallback the user is already looking at, instead of leaving the stale
 * value in place forever.
 */
export function basemapChange(
  catalog: readonly BasemapCatalogEntry[],
  stored: string | null | undefined,
  chosen: string,
): string | null {
  const next = resolveBasemapId(catalog, chosen)
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
 *
 * `opacity` is baked into the layers' own paint here, rather than left for a
 * post-`load` `setPaintProperty` call, because MapLibre's `load` event fires only
 * once the first frame is already drawn -- a style built at the default opacity would
 * paint one real frame at full strength before anything could turn it down.
 */
export function buildBasemapStyle(
  catalog: readonly BasemapCatalogEntry[],
  basemapId?: string | null,
  opacity = 1,
): StyleSpecification {
  const basemap = resolveBasemap(catalog, basemapId)
  // Absolute on purpose, same reason as tile URLs in `layerSpecs`: MapLibre may
  // resolve glyph templates without a reliable document base. Relative paths have
  // already bitten us for tiles; glyphs get the same treatment. Self-hosted, so
  // labels keep working for every basemap -- including "none".
  const origin = typeof window === 'undefined' ? '' : window.location.origin
  return {
    version: 8,
    glyphs: `${origin}/api/glyphs/{fontstack}/{range}.pbf`,
    sources: { ...basemap.sources },
    layers: basemap.layers.map((layer) => ({
      ...layer,
      paint: { ...layer.paint, 'raster-opacity': opacity },
    })),
  }
}

/** Fallback view when a project has neither a saved position nor any layer extent. */
export const GERMANY_VIEW = {
  center: [10.4515, 51.1657] satisfies [number, number],
  zoom: 5.2,
}
