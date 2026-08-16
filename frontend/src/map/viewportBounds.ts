import { geodesicDistance, type LngLat } from '@/measurement/geodesy'

/**
 * The rectangle a pitched map should report as its "current view", for callers that
 * cannot take anything but a rectangle -- a REST `bbox` query parameter among them.
 *
 * `Map.getBounds()` answers a different question than "aktueller Kartenausschnitt"
 * (`MapViewportTracker`) means to ask. It returns the rectangle that encloses whatever
 * is on screen, which is exactly right at pitch zero -- but a pitched view is a trapezoid
 * open toward the horizon, and MapLibre's own horizon clamp (`getMercatorHorizon`) still
 * lets that trapezoid's far edge reach however far the ground plane happens to extend
 * before it is judged out of view. Measured in the browser at zoom 6 (a wide, unremarkable
 * view of northern Germany) with 60 degrees of pitch -- the default ceiling -- the north
 * edge of `getBounds()` moved from Kiel to Stockholm for a few degrees of tilt that never
 * changed where the camera was looking. A checkbox that promises "only the current view"
 * should not silently grow to another country because the user tilted the camera to look
 * down a street.
 *
 * The fix here is not the true trapezoid -- a `bbox` parameter cannot hold one anyway --
 * but a tighter rectangle: the far (top-of-screen) edge is pulled in until it is no
 * farther from the map's centre than the near (bottom-of-screen) edge already is. The
 * near edge needs no correction; it stays close to the camera at any pitch and was never
 * the problem. Measured in the browser (zoom 6-18, pitch up to 60): this leaves the
 * rectangle byte-for-byte unchanged at pitch zero and untouched up to about 45 degrees of
 * tilt, and only pulls the far edge in at 60 -- exactly the range where `getBounds()`
 * itself starts to run away.
 */
export interface ViewportBoundsInput {
  /** CSS pixel size of the map canvas -- `getCanvas().clientWidth` / `clientHeight`. */
  width: number
  height: number
  center: LngLat
  /**
   * `Map.unproject`, taken as a callback rather than a live map so this stays testable
   * without WebGL -- the same split `snapping.ts`'s `Project` type uses for `project`.
   */
  unproject: (point: [number, number]) => LngLat
}

/** `[west, south, east, north]` -- the order the backend's `bbox` query parameter and `LngLatBounds.toArray()` both use. */
export type Bbox = [number, number, number, number]

/**
 * Binary-search steps for the far-edge clamp below. 20 halves the screen's own height
 * down to a fraction of a pixel, far tighter than a bbox query needs -- this only has to
 * land close enough that no caller notices the difference from an exact answer.
 */
const CLAMP_ITERATIONS = 20

export function viewportQueryBounds({ width, height, center, unproject }: ViewportBoundsInput): Bbox {
  const nearEdge = unproject([width / 2, height])
  const nearDistance = geodesicDistance(center, nearEdge)

  // A degenerate transform (zero-size canvas, a test's mocked `unproject`) has no
  // meaningful "near edge" to measure against -- fall back to the untouched top of the
  // canvas rather than search against a distance of zero, which would collapse the
  // rectangle onto the centre point.
  let topY = 0
  if (Number.isFinite(nearDistance) && nearDistance > 0) {
    let tooFar = 0
    let closeEnough = height / 2
    for (let i = 0; i < CLAMP_ITERATIONS; i += 1) {
      const midY = (tooFar + closeEnough) / 2
      const distance = geodesicDistance(center, unproject([width / 2, midY]))
      if (distance > nearDistance) tooFar = midY
      else closeEnough = midY
    }
    topY = closeEnough
  }

  // The same four-corner approach `Transform.getBounds()` uses, just with `topY` in
  // place of MapLibre's own (looser) horizon clamp. Plain min/max, not `LngLatBounds`'s
  // antimeridian-aware `extend()` -- hGIS never leaves Hamburg, so wrapping is not a
  // question this needs to answer, the same assumption every existing `getBounds()`
  // caller already made.
  let west = Infinity
  let south = Infinity
  let east = -Infinity
  let north = -Infinity
  for (const [x, y] of [[0, topY], [width, topY], [width, height], [0, height]] as const) {
    const [lng, lat] = unproject([x, y])
    west = Math.min(west, lng)
    east = Math.max(east, lng)
    south = Math.min(south, lat)
    north = Math.max(north, lat)
  }
  return [west, south, east, north]
}
