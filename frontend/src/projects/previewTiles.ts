import { resolveBasemap } from '@/map/basemap'
import type { ProjectSummary } from '@/api/projects'

/** Die vier Kachelbilder einer Vorschau, links oben beginnend. */
export interface PreviewTile {
  x: number
  y: number
  z: number
  url: string
}

type PreviewSource = Pick<ProjectSummary, 'center' | 'zoom' | 'extent' | 'basemap'>

/** The preview is a 2x2 grid of tiles -- see CONTRACT.md phase 22 on the tile server's usage rules. */
const GRID_TILES = 2

function lngToTileX(lng: number, z: number): number {
  return ((lng + 180) / 360) * 2 ** z
}

function latToTileY(lat: number, z: number): number {
  const radians = (lat * Math.PI) / 180
  return ((1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2) * 2 ** z
}

/** The one raster source a basemap definition carries. Undefined only for 'none'. */
function tileSource(basemapId: string | undefined) {
  return Object.values(resolveBasemap(basemapId).sources)[0]
}

/**
 * The deepest zoom the basemap's own tiles go to -- read from the source definition in
 * `map/basemap.ts` (19 for the OSM variants, 17 for OpenTopoMap) instead of repeating
 * those numbers here.
 */
function maxZoomFor(basemapId: string | undefined): number {
  return tileSource(basemapId)?.maxzoom ?? 19
}

function tileUrl(basemapId: string | undefined, x: number, y: number, z: number): string {
  const template = tileSource(basemapId)?.tiles?.[0] ?? ''
  return template
    .replace('{z}', String(z))
    .replace('{x}', String(x))
    .replace('{y}', String(y))
}

/** The largest zoom at which `extent` still spans no more than the 2x2 grid. */
function fitZoom(extent: readonly [number, number, number, number], maxZoom: number): number {
  const [minLng, minLat, maxLng, maxLat] = extent
  for (let z = maxZoom; z > 0; z--) {
    const width = lngToTileX(maxLng, z) - lngToTileX(minLng, z)
    const height = latToTileY(minLat, z) - latToTileY(maxLat, z)
    if (width <= GRID_TILES && height <= GRID_TILES) return z
  }
  return 0
}

function extentCenter(extent: readonly [number, number, number, number]): [number, number] {
  const [minLng, minLat, maxLng, maxLat] = extent
  return [(minLng + maxLng) / 2, (minLat + maxLat) / 2]
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

export function previewTilesFor(project: PreviewSource): PreviewTile[] {
  const { center, zoom, extent, basemap } = project

  // 'none' has no tile source to draw from, and crediting OSM for an empty canvas
  // would be attribution for data that is not on screen.
  if (basemap === 'none') return []
  // Nothing to center the grid on.
  if (!center && !extent) return []

  const maxZoom = maxZoomFor(basemap)
  const z = extent ? fitZoom(extent, maxZoom) : clamp(Math.round(zoom ?? 12), 0, maxZoom)
  const [lng, lat] = center ?? extentCenter(extent!)

  // A project at the edge of the world must not request a tile that does not exist,
  // so each index is clamped on its own to 0..2^z - 1.
  const tileMax = 2 ** z - 1
  const centerX = lngToTileX(lng, z)
  const centerY = latToTileY(lat, z)
  const x0 = clamp(Math.round(centerX) - 1, 0, tileMax)
  const x1 = clamp(x0 + 1, 0, tileMax)
  const y0 = clamp(Math.round(centerY) - 1, 0, tileMax)
  const y1 = clamp(y0 + 1, 0, tileMax)

  return [
    { x: x0, y: y0, z, url: tileUrl(basemap, x0, y0, z) },
    { x: x1, y: y0, z, url: tileUrl(basemap, x1, y0, z) },
    { x: x0, y: y1, z, url: tileUrl(basemap, x0, y1, z) },
    { x: x1, y: y1, z, url: tileUrl(basemap, x1, y1, z) },
  ]
}
