import { geodesicArea, pathLength, type LngLat } from './geodesy'

/** What is being measured. `null` as a mode means the tool is off. */
export type MeasureMode = 'distance' | 'area'

export interface MeasurementState {
  mode: MeasureMode | null
  /** The vertices the user placed, in click order. */
  points: LngLat[]
  /**
   * Where the pointer currently is. Counted into the result as a provisional last
   * vertex, which is what makes the number move while the mouse does -- the whole
   * point of a measuring tool over a "compute this geometry" button.
   */
  cursor: LngLat | null
  /** A finished sketch keeps its result on screen but no longer follows the pointer. */
  finished: boolean
}

export const IDLE: MeasurementState = {
  mode: null,
  points: [],
  cursor: null,
  finished: false,
}

/** A distance needs two points, an area three; below that there is nothing to show. */
function minimumPoints(mode: MeasureMode): number {
  return mode === 'area' ? 3 : 2
}

/**
 * Two clicks in the same spot.
 *
 * A double-click both finishes the sketch and delivers a second `click` at the very
 * same pixel, which unprojects to the very same coordinate. Without this the closing
 * double-click leaves a duplicate vertex behind -- harmless for the distance, but it
 * shows up as a stray marker and, on an area, as a zero-length spike.
 */
function isSamePlace(a: LngLat, b: LngLat): boolean {
  return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9
}

/**
 * Turns a mode on, or off again when it is already the active one, so the two toolbar
 * buttons behave like the toggles they look like. Switching between the two modes
 * starts over: a chain of points means something different in each.
 */
export function selectMode(state: MeasurementState, mode: MeasureMode): MeasurementState {
  if (state.mode === mode) return IDLE
  return { mode, points: [], cursor: null, finished: false }
}

export function exitMeasuring(): MeasurementState {
  return IDLE
}

/** Drops the geometry but stays in the mode -- "measure the next thing". */
export function clearSketch(state: MeasurementState): MeasurementState {
  if (state.mode === null) return state
  return { mode: state.mode, points: [], cursor: null, finished: false }
}

/**
 * Places a vertex. On a finished sketch this begins the next measurement instead of
 * extending the old one, so measuring twice in a row costs one click, not two.
 */
export function addVertex(state: MeasurementState, point: LngLat): MeasurementState {
  if (state.mode === null) return state
  if (state.finished) {
    return { mode: state.mode, points: [point], cursor: null, finished: false }
  }

  const last = state.points[state.points.length - 1]
  if (last && isSamePlace(last, point)) return state

  return { ...state, points: [...state.points, point] }
}

/** Takes back the last vertex; the sketch stays open. */
export function undoVertex(state: MeasurementState): MeasurementState {
  if (state.mode === null || state.finished || state.points.length === 0) return state
  return { ...state, points: state.points.slice(0, -1) }
}

export function moveCursor(state: MeasurementState, point: LngLat | null): MeasurementState {
  if (state.mode === null || state.finished) return state
  return { ...state, cursor: point }
}

/**
 * True while there is a sketch that closing would turn into a result.
 *
 * Drives the toolbar's finish button, which is the only way to close a sketch without
 * a double-click -- and therefore the only way at all on a touch screen.
 */
export function canFinishSketch(state: MeasurementState): boolean {
  if (state.mode === null || state.finished) return false
  return state.points.length >= minimumPoints(state.mode)
}

/**
 * Closes the sketch. Too few points to measure anything means the attempt is discarded
 * rather than frozen -- a finished one-point "distance" would be a result of 0 m that
 * looks like a measurement.
 */
export function finishSketch(state: MeasurementState): MeasurementState {
  if (state.mode === null) return state
  if (state.finished) return state
  if (state.points.length < minimumPoints(state.mode)) return clearSketch(state)
  return { ...state, cursor: null, finished: true }
}

/**
 * The vertices as they should be measured and drawn: the placed ones, plus the pointer
 * as a provisional last one while the sketch is open.
 */
export function sketchPath(state: MeasurementState): LngLat[] {
  if (state.finished || !state.cursor) return state.points
  const last = state.points[state.points.length - 1]
  if (last && isSamePlace(last, state.cursor)) return state.points
  return [...state.points, state.cursor]
}

export interface MeasurementResult {
  mode: MeasureMode
  /** Length of the path, or the perimeter of the closed ring when measuring an area. */
  length: number
  /** Square metres; null while measuring a distance. */
  area: number | null
  /** Placed vertices, not counting the pointer -- this is what the hint text counts down. */
  vertexCount: number
  /** True once the result stands for a closed sketch rather than a moving one. */
  finished: boolean
  /** False while there are too few points for the number to mean anything. */
  meaningful: boolean
}

export function measurementResult(state: MeasurementState): MeasurementResult | null {
  if (state.mode === null) return null

  const path = sketchPath(state)
  const meaningful = path.length >= minimumPoints(state.mode)

  if (state.mode === 'area') {
    // The perimeter includes the closing leg back to the first vertex, because that
    // leg is drawn and therefore part of what the user sees being measured.
    const ring = meaningful ? [...path, path[0]] : path
    return {
      mode: 'area',
      length: pathLength(ring),
      area: meaningful ? geodesicArea(path) : 0,
      vertexCount: state.points.length,
      finished: state.finished,
      meaningful,
    }
  }

  return {
    mode: 'distance',
    length: pathLength(path),
    area: null,
    vertexCount: state.points.length,
    finished: state.finished,
    meaningful,
  }
}

/**
 * The sketch as GeoJSON for the map. Kept a pure function of the state so the drawing
 * can be asserted in a test without a map.
 *
 * Three roles, and the split between them is what tells the user which part of the
 * shape they have actually committed to: `area` fills the ring, `line` is everything
 * already clicked, `pending` is what merely follows the pointer -- the leg to the
 * cursor and, for an area, the leg that closes the ring. The map styles `pending`
 * dashed, so the rubber band never looks like a placed edge.
 */
export function sketchFeatures(state: MeasurementState): GeoJSON.FeatureCollection {
  const path = sketchPath(state)
  const features: GeoJSON.Feature[] = []

  if (state.mode === 'area' && path.length >= 3) {
    features.push({
      type: 'Feature',
      properties: { role: 'area' },
      geometry: { type: 'Polygon', coordinates: [[...path, path[0]]] },
    })
  }

  // A finished ring has no pending leg left, so its closing edge becomes solid.
  const settled =
    state.mode === 'area' && state.finished && state.points.length >= 3
      ? [...state.points, state.points[0]]
      : state.points
  if (settled.length >= 2) {
    features.push({
      type: 'Feature',
      properties: { role: 'line' },
      geometry: { type: 'LineString', coordinates: settled },
    })
  }

  const pending: LngLat[][] = []
  if (!state.finished && state.cursor && state.points.length >= 1) {
    pending.push([state.points[state.points.length - 1], state.cursor])
  }
  if (state.mode === 'area' && !state.finished && path.length >= 3) {
    pending.push([path[path.length - 1], path[0]])
  }
  if (pending.length > 0) {
    features.push({
      type: 'Feature',
      properties: { role: 'pending' },
      geometry: { type: 'MultiLineString', coordinates: pending },
    })
  }

  for (const point of state.points) {
    features.push({
      type: 'Feature',
      properties: { role: 'vertex' },
      geometry: { type: 'Point', coordinates: point },
    })
  }

  return { type: 'FeatureCollection', features }
}
