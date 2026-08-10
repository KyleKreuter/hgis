/**
 * Bounding box of a GeoJSON geometry, as [minLng, minLat, maxLng, maxLat].
 *
 * Written out rather than pulled from a library because it is a fold over nested
 * coordinate arrays and nothing else: every GeoJSON geometry is arrays of arrays
 * bottoming out in [lng, lat] pairs, so one recursive walk covers Point through
 * MultiPolygon without a case per type.
 *
 * Returns null for an empty geometry -- a caller cannot zoom to nothing, and a box of
 * Infinities would silently send the map to the other side of the world.
 */
export function boundsOfGeometry(
  geometry: { type: string; coordinates: unknown } | null | undefined,
): [number, number, number, number] | null {
  if (!geometry?.coordinates) return null

  let minLng = Infinity
  let minLat = Infinity
  let maxLng = -Infinity
  let maxLat = -Infinity
  let found = false

  function walk(node: unknown): void {
    if (!Array.isArray(node)) return

    if (typeof node[0] === 'number' && typeof node[1] === 'number') {
      const [lng, lat] = node as [number, number]
      minLng = Math.min(minLng, lng)
      minLat = Math.min(minLat, lat)
      maxLng = Math.max(maxLng, lng)
      maxLat = Math.max(maxLat, lat)
      found = true
      return
    }
    for (const child of node) walk(child)
  }

  walk(geometry.coordinates)
  return found ? [minLng, minLat, maxLng, maxLat] : null
}
