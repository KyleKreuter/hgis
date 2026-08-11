/** Allowed zoom window on a layer -- matches `LayerDtos.UpdateRequest`. */
export const LAYER_ZOOM_MIN = 0
export const LAYER_ZOOM_MAX = 24

function clampZoom(value: number): number {
  return Math.min(LAYER_ZOOM_MAX, Math.max(LAYER_ZOOM_MIN, Math.round(value)))
}

/**
 * New min with max pulled up when needed.
 *
 * The optimistic PATCH must stay in a state the server accepts (`minZoom ≤ maxZoom`).
 */
export function withMinZoom(minZoom: number, maxZoom: number): { minZoom: number; maxZoom: number } {
  const min = clampZoom(minZoom)
  return { minZoom: min, maxZoom: Math.max(min, clampZoom(maxZoom)) }
}

/** New max with min pulled down when needed. */
export function withMaxZoom(minZoom: number, maxZoom: number): { minZoom: number; maxZoom: number } {
  const max = clampZoom(maxZoom)
  return { minZoom: Math.min(max, clampZoom(minZoom)), maxZoom: max }
}
