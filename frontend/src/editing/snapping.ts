/**
 * Snapping against full-precision geometry (plan section D.1).
 *
 * The candidates never come from vector tiles. `ST_AsMVTGeom` quantises coordinates to a
 * 4096-unit grid per tile and simplifies what it renders, so a vertex snapped to tile
 * geometry *looks* like a hit and sits centimetres to metres away from the real one --
 * and gaps like that between neighbouring parcels only surface years later. The editor
 * loads its features from the feature API instead, which returns exact coordinates.
 *
 * The snapped position is passed through unchanged, never rebuilt from screen pixels:
 * the whole point is to land on the target coordinate exactly, not close to it.
 */

export type SnapKind = 'vertex' | 'edge'

export interface SnapTarget {
  position: [number, number]
  kind: SnapKind
  /** Distance to the pointer in screen pixels -- what the tolerance is measured in. */
  distancePx: number
}

export interface SnapCandidate {
  geometry: GeoJSON.Geometry
  /** [minLng, minLat, maxLng, maxLat], precomputed so each pointer move is a cheap reject. */
  bounds: [number, number, number, number]
}

/** Projects a coordinate to screen pixels; supplied by the map. */
export type Project = (position: [number, number]) => { x: number; y: number }

/**
 * Tolerance in screen pixels, not metres.
 *
 * A fixed distance in map units would make snapping useless when zoomed out (everything
 * within tolerance) and unreachable when zoomed in (nothing ever is). Twelve pixels is
 * roughly what a pointer can be aimed at.
 */
export const SNAP_TOLERANCE_PX = 12

function distance(a: { x: number; y: number }, b: { x: number; y: number }): number {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

/** Every ring or line of a geometry, as flat coordinate lists. */
function* linesOf(geometry: GeoJSON.Geometry): Generator<GeoJSON.Position[]> {
  switch (geometry.type) {
    case 'Point':
      yield [geometry.coordinates]
      break
    case 'MultiPoint':
    case 'LineString':
      yield geometry.coordinates
      break
    case 'MultiLineString':
    case 'Polygon':
      yield* geometry.coordinates
      break
    case 'MultiPolygon':
      for (const polygon of geometry.coordinates) yield* polygon
      break
    default:
      break
  }
}

/**
 * Closest point on the segment a-b to p, all in screen space.
 *
 * Returned as a ratio along the segment rather than as a coordinate, so the caller can
 * interpolate in map coordinates instead -- interpolating in pixels and unprojecting
 * would reintroduce exactly the rounding this module exists to avoid.
 */
function projectOntoSegment(
  p: { x: number; y: number },
  a: { x: number; y: number },
  b: { x: number; y: number },
): { ratio: number; point: { x: number; y: number } } {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const lengthSquared = dx * dx + dy * dy

  if (lengthSquared === 0) {
    return { ratio: 0, point: a }
  }

  // Clamped, so a point beyond either end snaps to that end rather than to the
  // segment's infinite extension.
  const ratio = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared))
  return { ratio, point: { x: a.x + ratio * dx, y: a.y + ratio * dy } }
}

/**
 * Finds what the pointer should snap to.
 *
 * Vertices win over edges even when an edge is closer: a shared corner is almost always
 * what someone aims for, and having the cursor slide onto the line right next to it is
 * the classic frustration of a snapping tool that only ranks by distance.
 *
 * @param pointer pointer position in map coordinates
 * @param candidates geometries to snap to, with precomputed bounds
 * @param project map coordinate to screen pixels
 * @param tolerancePx how close the pointer has to be, in pixels
 */
export function findSnapTarget(
  pointer: [number, number],
  candidates: SnapCandidate[],
  project: Project,
  tolerancePx: number = SNAP_TOLERANCE_PX,
): SnapTarget | null {
  const pointerPx = project(pointer)

  // The tolerance is a pixel radius; to reject candidates by their bounds it has to be
  // expressed in map units, which depends on the current zoom and latitude.
  const toleranceInMapUnits = mapUnitsPerPixel(pointer, project) * tolerancePx

  let bestVertex: SnapTarget | null = null
  let bestEdge: SnapTarget | null = null

  for (const candidate of candidates) {
    const [minLng, minLat, maxLng, maxLat] = candidate.bounds
    if (
      pointer[0] < minLng - toleranceInMapUnits ||
      pointer[0] > maxLng + toleranceInMapUnits ||
      pointer[1] < minLat - toleranceInMapUnits ||
      pointer[1] > maxLat + toleranceInMapUnits
    ) {
      continue
    }

    for (const line of linesOf(candidate.geometry)) {
      for (let i = 0; i < line.length; i++) {
        const coordinate = line[i] as [number, number]
        const vertexPx = project(coordinate)
        const vertexDistance = distance(pointerPx, vertexPx)

        if (vertexDistance <= tolerancePx && (!bestVertex || vertexDistance < bestVertex.distancePx)) {
          // Passed through untouched -- this is the coordinate the target actually has.
          bestVertex = { position: coordinate, kind: 'vertex', distancePx: vertexDistance }
        }

        if (i === 0) continue
        const previous = line[i - 1] as [number, number]
        const previousPx = project(previous)
        const { ratio, point } = projectOntoSegment(pointerPx, previousPx, vertexPx)
        const edgeDistance = distance(pointerPx, point)

        if (edgeDistance <= tolerancePx && (!bestEdge || edgeDistance < bestEdge.distancePx)) {
          bestEdge = {
            // Interpolated in map coordinates, not unprojected from pixels.
            position: [
              previous[0] + ratio * (coordinate[0] - previous[0]),
              previous[1] + ratio * (coordinate[1] - previous[1]),
            ],
            kind: 'edge',
            distancePx: edgeDistance,
          }
        }
      }
    }
  }

  return bestVertex ?? bestEdge
}

/**
 * Size of one screen pixel in map units at the pointer's position.
 *
 * Measured by projecting two nearby coordinates rather than derived from the zoom level:
 * the scale depends on latitude in a Mercator projection, and the map is the only thing
 * that knows its own transform.
 */
function mapUnitsPerPixel(pointer: [number, number], project: Project): number {
  const step = 0.0001
  const origin = project(pointer)
  const offset = project([pointer[0] + step, pointer[1]])
  const pixels = Math.abs(offset.x - origin.x)

  // Degenerate transform (zero-size map, mocked projection): fall back to a value that
  // makes the bounds check permissive rather than rejecting every candidate.
  return pixels === 0 ? Infinity : step / pixels
}

/** Bounding box of a geometry, for the cheap rejection above. */
export function boundsOf(geometry: GeoJSON.Geometry): [number, number, number, number] {
  let minLng = Infinity
  let minLat = Infinity
  let maxLng = -Infinity
  let maxLat = -Infinity

  for (const line of linesOf(geometry)) {
    for (const [lng, lat] of line as [number, number][]) {
      minLng = Math.min(minLng, lng)
      minLat = Math.min(minLat, lat)
      maxLng = Math.max(maxLng, lng)
      maxLat = Math.max(maxLat, lat)
    }
  }
  return [minLng, minLat, maxLng, maxLat]
}
