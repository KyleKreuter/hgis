import type { StyleSpecification } from 'maplibre-gl'

/**
 * Raises the detail level of raster basemaps for an export.
 *
 * Vector data scales for free: it is re-rendered at whatever pixel ratio the export asks
 * for, so labels and lines come out sharp. A raster basemap does not. Its tiles are
 * finished images of a fixed size, and a higher pixel ratio only stretches them -- which
 * is why an export at 300 dpi has crisp points sitting on a blurry map.
 *
 * The fix is to make MapLibre fetch tiles from a deeper zoom level. It picks that level
 * from `tileSize`: a source declaring 256 loads one level below the map's own zoom, one
 * declaring 128 loads two. Dividing `tileSize` by the pixel ratio therefore buys exactly
 * the detail the extra pixels can show.
 */

/** MapLibre's own tile size, the yardstick its zoom arithmetic is written against. */
const MAPLIBRE_TILE_SIZE = 512

/**
 * Two levels, so 16 times the tiles.
 *
 * Not a rendering limit but a courtesy one: an A4 landscape page at 300 dpi already
 * fetches upwards of 200 tiles at this depth, and public tile servers are donated
 * infrastructure. A third level would quadruple that again for detail no printer
 * resolves.
 */
const MAX_EXTRA_LEVELS = 2

/**
 * How many zoom levels deeper to fetch for a given pixel ratio.
 *
 * Rounded, not truncated: a ratio of 3.125 sits at 1.64 levels, and stopping at one
 * would leave the basemap visibly softer than the data drawn over it. Rounding up past
 * what the pixels can show costs a download and no quality.
 */
export function extraZoomLevels(pixelRatio: number): number {
  if (!Number.isFinite(pixelRatio) || pixelRatio <= 1) return 0
  return Math.min(Math.round(Math.log2(pixelRatio)), MAX_EXTRA_LEVELS)
}

/**
 * The style with every raster source set to fetch deeper tiles.
 *
 * Returns the style unchanged when there is nothing to gain, so an export at screen
 * resolution behaves exactly as before.
 *
 * `raster-dem` is left alone deliberately: terrain is sampled for elevation, not drawn,
 * and finer height data would change the geometry rather than sharpen it.
 */
export function sharpenRasterSources(style: StyleSpecification, pixelRatio: number): StyleSpecification {
  const levels = extraZoomLevels(pixelRatio)
  if (levels === 0) return style

  const factor = 2 ** levels
  let touched = false
  const sources: StyleSpecification['sources'] = {}

  for (const [id, source] of Object.entries(style.sources)) {
    if (source.type !== 'raster') {
      sources[id] = source
      continue
    }
    const declared = source.tileSize ?? MAPLIBRE_TILE_SIZE
    const sharpened = declared / factor
    // Below 32 the tile count stops buying detail and starts buying requests: at that
    // size a single screen already needs hundreds, and most servers have no such level
    // to give.
    if (sharpened < 32) {
      sources[id] = source
      continue
    }
    sources[id] = { ...source, tileSize: sharpened }
    touched = true
  }

  return touched ? { ...style, sources } : style
}
