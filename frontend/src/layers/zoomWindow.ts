/**
 * Whether a layer's zoom window covers the zoom the map is standing at.
 *
 * A WMS service declares the scale range in which each of its layers draws anything at
 * all, and `MapLayerService` turns that into the layer's `minZoom`/`maxZoom`. Outside it
 * the layer is simply not on the map -- correct, and completely invisible in the layer
 * tree, where the entry sits there with its tick set as if it were being drawn. That gap
 * is what this module exists to close.
 *
 * Kept apart from the tree component so the decision can be tested without rendering,
 * and shared with the toast the map image picker shows right after adding a layer -- one
 * rule, two places, no chance of the two drifting apart.
 */

export type ZoomWindowState = 'inside' | 'below' | 'above' | 'unknown'

/**
 * Where the current zoom sits relative to the window.
 *
 * `'unknown'` while the map has not reported a zoom yet: a guess about what the user can
 * see is worse than saying nothing.
 *
 * Both bounds count as inside. MapLibre itself treats a layer's `maxzoom` as exclusive,
 * so a layer whose window ends at 22 is already hidden at exactly 22 -- a boundary only
 * reachable by standing on the map's own limit, where claiming "not visible" would read
 * as a bug rather than as the hairline case it is.
 */
export function zoomWindowState(
  minZoom: number,
  maxZoom: number,
  currentZoom: number | null,
): ZoomWindowState {
  if (currentZoom === null) return 'unknown'
  if (currentZoom < minZoom) return 'below'
  if (currentZoom > maxZoom) return 'above'
  return 'inside'
}

/**
 * The sentence that explains the state, or null when there is nothing to explain.
 *
 * Names the bound the user has to cross and where they are now -- "ab Zoom 16" alone
 * leaves them counting, and the zoom level is not on screen anywhere else.
 */
export function describeZoomWindow(
  minZoom: number,
  maxZoom: number,
  currentZoom: number | null,
): string | null {
  const state = zoomWindowState(minZoom, maxZoom, currentZoom)
  if (state === 'below') return `Sichtbar ab Zoom ${minZoom} — Sie sind bei ${Math.round(currentZoom as number)}.`
  if (state === 'above') return `Sichtbar bis Zoom ${maxZoom} — Sie sind bei ${Math.round(currentZoom as number)}.`
  return null
}
