/**
 * What goes on the image besides the map itself (CONTRACT.md 13.1): title, north arrow,
 * scale bar, attribution.
 *
 * This module decides *what* is drawn, `drawFurniture.ts` decides how. The split exists
 * so the four rules that carry meaning can be tested without a canvas:
 *
 * - the scale bar belongs to the export's own zoom, so a different page format changes it
 * - the north arrow appears only on a map that is turned or tilted, where "up" is no
 *   longer north and the reader would otherwise have no way to tell
 * - the attribution is always there. It is a licence term, not a decoration, and this
 *   function takes no option that could leave it out
 * - an empty title draws nothing rather than an empty box
 */

import { attributionText, type AttributionPart } from '../basemap'
import { computeScaleBar } from '../scale'

/**
 * How much of the image width the scale bar may take, and its ceiling in CSS pixels.
 * A quarter of a narrow image still reads well, while an unbounded quarter of A3 would
 * be a 20 cm ruler across the page.
 */
const SCALE_BAR_MAX_FRACTION = 0.25
const SCALE_BAR_MAX_CSS_PX = 200

/** Below this, a bearing or pitch is a rounding remnant of resetting the map, not a rotation. */
const ROTATION_EPSILON = 0.01

export interface FurnitureInput {
  /** User-typed, prefilled with the project name. Blank means no title on the image. */
  title: string
  /** Latitude of the export's centre -- metres per pixel shrink towards the poles. */
  centerLat: number
  /** The export's own zoom (see `exportView.ts`), never the screen's. */
  zoom: number
  bearing: number
  pitch: number
  /** The export's box in CSS pixels, so the bar is sized like every other piece. */
  cssWidth: number
  /** Basemap notice plus every visible Geoportal layer's, already combined by the caller. */
  attribution: readonly AttributionPart[]
}

export interface ScaleBarPlan {
  /** In CSS pixels; `drawFurniture` multiplies by the pixel ratio like every other length. */
  widthCssPx: number
  label: string
}

export interface FurniturePlan {
  title: string | null
  /** The map's bearing, or null while north is up and the map is flat. */
  northArrow: { bearing: number } | null
  /** Null only where the map is degenerate enough to have no scale at all. */
  scaleBar: ScaleBarPlan | null
  /** Empty only when there is genuinely nothing to credit ("Keine Hintergrundkarte"). */
  attribution: string
}

/** MapLibre reports [-180, 180]; a value from elsewhere is folded into that range. */
function normalizeBearing(bearing: number): number {
  const wrapped = ((bearing % 360) + 540) % 360 - 180
  return wrapped
}

export function buildFurniture(input: FurnitureInput): FurniturePlan {
  const title = input.title.trim()
  const bearing = normalizeBearing(input.bearing)
  const rotated = Math.abs(bearing) > ROTATION_EPSILON || Math.abs(input.pitch) > ROTATION_EPSILON

  const maxBarWidth = Math.min(input.cssWidth * SCALE_BAR_MAX_FRACTION, SCALE_BAR_MAX_CSS_PX)
  const bar = computeScaleBar(input.centerLat, input.zoom, maxBarWidth)

  return {
    title: title.length > 0 ? title : null,
    northArrow: rotated ? { bearing } : null,
    scaleBar: bar.widthPx > 0 ? { widthCssPx: bar.widthPx, label: bar.label } : null,
    attribution: attributionText(input.attribution),
  }
}
