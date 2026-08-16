import { geodesicDistance, type LngLat } from '@/measurement/geodesy'
import { metersPerPixel } from './scale'

/** `[west, south, east, north]` -- the order the backend's `bbox` query parameter and `LngLatBounds.toArray()` both use. */
export type Bbox = [number, number, number, number]

/**
 * How many times a flat view's own diagonal the reported bbox may span before pitch
 * counts as having "expanded" it.
 *
 * Measured in the browser (zoom 6-18, pitch up to the 60 degree ceiling) as the ratio
 * between `Map.getBounds()`'s diagonal and a flat view's at the same zoom: it holds
 * under 1.3 at 30 degrees of pitch and under 1.6 at 45, regardless of zoom -- ordinary
 * looking-around tilts nobody needs a warning for. It passes 2.5 at the 60 degree
 * ceiling. 1.5 sits between those two regimes: quiet through an ordinary tilt, and lit
 * by the time pitch has genuinely pulled in ground far outside what the zoom level
 * itself would suggest.
 */
const EXPANSION_RATIO = 1.5

export interface PitchExpansionInput {
  bbox: Bbox
  /** CSS pixel size of the map canvas -- `getCanvas().clientWidth` / `clientHeight`. */
  width: number
  height: number
  center: LngLat
  zoom: number
}

/**
 * Whether `bbox` -- `Map.getBounds()`, unmodified -- reaches far enough past what the
 * current zoom level would show flat that pitch, not the zoom, is what grew it.
 *
 * `Map.getBounds()` is left untouched everywhere in this codebase (see `DrawController`'s
 * two callers) because it is a *complete* rectangle: it always contains everything on
 * screen, even at steep pitch, so nothing visible ever falls outside a bbox-filtered
 * query built from it. An earlier version of this file instead pulled the far edge of
 * that rectangle in to bound how large it could get -- which also shrank the west and
 * east edges, since under pitch the widest points of the visible trapezoid sit at the
 * far corners, not the near ones. That traded a correctness bug (a checkbox promising
 * "the current view" reaching another country) for a worse one (features visibly on
 * screen quietly missing from an export). Completeness has to win: nothing on screen may
 * be left out silently.
 *
 * What is left is telling the user honestly instead: `MapViewportTracker` reports this
 * alongside the untouched bbox, and the Geoportal dialog's "aktueller Kartenausschnitt"
 * checkbox shows a note when it is true, the same way `DrawController`'s `MAX_EDITABLE`
 * turns an oversized load into a stated warning rather than a silent one.
 */
export function isPitchExpanded({ bbox, width, height, center, zoom }: PitchExpansionInput): boolean {
  const [west, south, east, north] = bbox
  const actualDiagonal = geodesicDistance([west, south], [east, north])
  const flatDiagonal = metersPerPixel(center[1], zoom) * Math.hypot(width, height)

  // Degenerate transform (zero-size canvas): nothing to compare against.
  if (!Number.isFinite(flatDiagonal) || flatDiagonal <= 0) return false
  return actualDiagonal > flatDiagonal * EXPANSION_RATIO
}
