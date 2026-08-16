import { geodesicDistance, type LngLat } from '@/measurement/geodesy'
import { metersPerPixel } from './scale'

/** `[west, south, east, north]` -- the order the backend's `bbox` query parameter and `LngLatBounds.toArray()` both use. */
export type Bbox = [number, number, number, number]

/**
 * The two ratio thresholds a hysteresis band needs: how far the bbox's own diagonal may
 * exceed a flat view's at the same zoom before pitch counts as having "expanded" it, and
 * how far it must fall back before that stops being true.
 *
 * Measured in the browser (zoom 6-18, pitch up to the 60 degree ceiling): the ratio
 * rises *smoothly and monotonically* with both zoom and pitch -- no jumps, one crossing
 * each way -- but at 45 degrees of pitch the slope is only about 0.03 per degree,
 * essentially the same at every zoom tested. A single threshold sitting inside that
 * shallow a slope means roughly one degree of pitch flips it, and a mouse-driven tilt
 * gesture does not hold within one degree -- the note would flicker on and off while the
 * user was still turning the knob.
 *
 * `LOW`/`HIGH` fix that by requiring the ratio to cross a wider band before the note
 * changes state at all, not just a single value: `HIGH` to switch it on, `LOW` to switch
 * it back off, with `HIGH` never used to turn it off nor `LOW` to turn it on. At the
 * measured slope, 1.4 to 1.6 spans roughly 40.3 to 47.3 degrees of pitch -- about 7
 * degrees, well past what a tremor while dragging the tilt control covers, without
 * pushing the band anywhere near the 30 degree tilts this note was never meant to fire
 * for.
 */
const EXPANSION_RATIO_HIGH = 1.6
const EXPANSION_RATIO_LOW = 1.4

export interface PitchExpansionInput {
  bbox: Bbox
  /** CSS pixel size of the map canvas -- `getCanvas().clientWidth` / `clientHeight`. */
  width: number
  height: number
  center: LngLat
  zoom: number
  /** What this same check last decided, so it knows which of the two thresholds to hold it to. */
  wasExpanded: boolean
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
 *
 * Hysteresis, not a single threshold (see `EXPANSION_RATIO_HIGH`/`_LOW`): `wasExpanded`
 * is the caller's own memory of the last answer, which is also why this function takes
 * it as a parameter rather than keeping it itself -- a pure function stays trivial to
 * test with an arbitrary starting state, where a function that remembered its own last
 * answer would not be.
 */
export function isPitchExpanded({ bbox, width, height, center, zoom, wasExpanded }: PitchExpansionInput): boolean {
  const [west, south, east, north] = bbox
  const actualDiagonal = geodesicDistance([west, south], [east, north])
  const flatDiagonal = metersPerPixel(center[1], zoom) * Math.hypot(width, height)

  // Degenerate transform (zero-size canvas): nothing to compare against.
  if (!Number.isFinite(flatDiagonal) || flatDiagonal <= 0) return false
  const ratio = actualDiagonal / flatDiagonal
  return wasExpanded ? ratio > EXPANSION_RATIO_LOW : ratio > EXPANSION_RATIO_HIGH
}
