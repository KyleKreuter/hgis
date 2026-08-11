/**
 * Screen-rectangle-to-bbox math for the rectangle select tool.
 *
 * Kept apart from `RectangleSelectTool` so the corner-normalisation and drag-threshold
 * rules can be tested without a MapLibre instance -- the controller only ever supplies
 * two already-projected corners.
 */

export type Bbox = [number, number, number, number]

/** Normalises two arbitrary corners (dragged in any direction) into [minLng, minLat, maxLng, maxLat]. */
export function bboxFromCorners(a: readonly [number, number], b: readonly [number, number]): Bbox {
  const minLng = Math.min(a[0], b[0])
  const maxLng = Math.max(a[0], b[0])
  const minLat = Math.min(a[1], b[1])
  const maxLat = Math.max(a[1], b[1])
  return [minLng, minLat, maxLng, maxLat]
}

/**
 * Whether a drag moved far enough to count as a deliberate rectangle rather than a
 * stray click. Without this, releasing the mouse at (almost) the same pixel it went
 * down at would fire a query for a point-sized bbox on every accidental click while
 * the tool is armed.
 *
 * Takes screen pixels as `[x, y]` tuples, the same shape as MapLibre's `PointLike`,
 * so the controller can pass its points through without converting either way.
 */
export function isMeaningfulDrag(
  start: readonly [number, number],
  end: readonly [number, number],
  thresholdPx = 3,
): boolean {
  return Math.abs(end[0] - start[0]) >= thresholdPx || Math.abs(end[1] - start[1]) >= thresholdPx
}

/** The rectangle as a closed GeoJSON ring, for the sketch layer drawn while dragging. */
export function rectanglePolygon(bbox: Bbox): GeoJSON.Polygon {
  const [minLng, minLat, maxLng, maxLat] = bbox
  return {
    type: 'Polygon',
    coordinates: [
      [
        [minLng, minLat],
        [maxLng, minLat],
        [maxLng, maxLat],
        [minLng, maxLat],
        [minLng, minLat],
      ],
    ],
  }
}
