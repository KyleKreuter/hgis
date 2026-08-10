import type { StyleSpecification } from 'maplibre-gl'

/**
 * OSM raster tiles as the only basemap source, no API key required. Kept as a
 * plain raster style (not a vector basemap) so phase 3 has zero external service
 * dependencies beyond OSM's own tile server.
 *
 * Attribution is rendered as our own element (see `MapCanvas`) instead of MapLibre's
 * default `AttributionControl`, in line with "eigene Bedienelemente statt der
 * MapLibre-Defaults" -- the control is disabled in the Map constructor.
 */
export const OSM_BASEMAP_STYLE: StyleSpecification = {
  version: 8,
  sources: {
    osm: {
      type: 'raster',
      tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
      tileSize: 256,
      // OSM serves nothing past zoom 19. Without this MapLibre keeps requesting deeper
      // tiles, every one of them fails, and the basemap disappears exactly where editing
      // happens -- with a burst of console errors and no visible reason. Declared, it
      // scales the level-19 tiles up instead.
      maxzoom: 19,
      attribution: '© OpenStreetMap contributors',
    },
  },
  layers: [
    {
      id: 'osm',
      type: 'raster',
      source: 'osm',
    },
  ],
}

export const OSM_ATTRIBUTION = '© OpenStreetMap contributors'

/** Fallback view when a project has neither a saved position nor any layer extent. */
export const GERMANY_VIEW = {
  center: [10.4515, 51.1657] satisfies [number, number],
  zoom: 5.2,
}
