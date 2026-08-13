import type { Map as MapLibreMap } from 'maplibre-gl'

/**
 * Which layers are overlays, and in which order they belong above the data.
 *
 * Four components add layers to the same style without knowing about each other:
 * `syncMapLayers` reconciles the catalog and finishes by moving every data layer to
 * the top, `SelectionHighlight` adds a highlight per selected feature, `MeasurementLayer`
 * draws its sketch, and `RectangleSelectTool` draws the rectangle being dragged.
 * Whoever ran last used to win -- so toggling a layer's visibility, changing a colour
 * or reordering the tree pushed the data over a running measurement, and the sketch
 * disappeared under it.
 *
 * The fix is one shared rule instead of three private ones: an overlay is recognised
 * by its id, every writer calls `raiseOverlays` when it is done, and the tier list
 * below decides the order among the overlays themselves. That way measurement and
 * selection are not played off against each other -- both end up on top, in a defined
 * order, no matter who touched the style last.
 */

/** Id namespace of the selection highlight layers -- see `SelectionHighlight`. */
export const SELECTION_LAYER_SUFFIX = '-selected'

/** Id namespace of the measurement sketch layers -- see `MeasurementLayer`. */
export const MEASUREMENT_LAYER_PREFIX = 'hgis-measurement'

/** Id namespace of the rectangle select sketch layers -- see `RectangleSelectTool`. */
export const RECTANGLE_SELECT_LAYER_PREFIX = 'hgis-rectangle-select'

/** Id namespace of the split line sketch layers -- see `SplitLineTool`. */
export const SPLIT_LINE_LAYER_PREFIX = 'hgis-split-line'

/**
 * Bottom to top. The selection belongs above the data it points at; the measurement
 * sketch, the rectangle being dragged and the split line belong above everything,
 * because each is the one thing being drawn right now and a vertex or a corner hidden
 * under a highlight cannot be placed with any confidence.
 *
 * The split line is the one that has to be highest: it is drawn over an object that is
 * selected at the same time -- the selection is what said which object gets cut -- so
 * unlike the other three it genuinely overlaps the highlight, every time.
 */
const TIERS: readonly ((layerId: string) => boolean)[] = [
  (layerId) => layerId.includes(SELECTION_LAYER_SUFFIX),
  (layerId) => layerId.startsWith(MEASUREMENT_LAYER_PREFIX),
  (layerId) => layerId.startsWith(RECTANGLE_SELECT_LAYER_PREFIX),
  (layerId) => layerId.startsWith(SPLIT_LINE_LAYER_PREFIX),
]

/** The tier a layer belongs to, or -1 when it is not an overlay at all. */
export function overlayTier(layerId: string): number {
  return TIERS.findIndex((matches) => matches(layerId))
}

export function isOverlayLayer(layerId: string): boolean {
  return overlayTier(layerId) >= 0
}

/**
 * The overlay layers among `layerIds`, in the order they have to be moved to the top
 * of the style to end up correctly stacked.
 *
 * Ties keep the order they already had, which is what preserves the internal stacking
 * of a group -- a selection's fill stays below its outline, and the measurement's fill
 * below its lines and vertices.
 */
export function overlayOrder(layerIds: readonly string[]): string[] {
  return layerIds
    .map((id, index) => ({ id, tier: overlayTier(id), index }))
    .filter((entry) => entry.tier >= 0)
    .sort((a, b) => a.tier - b.tier || a.index - b.index)
    .map((entry) => entry.id)
}

/** The subset of maplibregl.Map this module touches; a plain object satisfies it in tests. */
export type OverlayMapLike = Pick<MapLibreMap, 'getStyle' | 'getLayer' | 'moveLayer'>

/**
 * Lifts every overlay back above the data layers, in tier order.
 *
 * Called by everything that reorders or adds layers. `moveLayer` without a `beforeId`
 * moves to the very top, so walking the overlays bottom-up leaves them stacked exactly
 * as `overlayOrder` prescribes. A style without overlays makes this a no-op.
 */
export function raiseOverlays(map: OverlayMapLike): void {
  const layerIds = (map.getStyle()?.layers ?? []).map((layer) => layer.id)
  for (const layerId of overlayOrder(layerIds)) {
    if (map.getLayer(layerId)) map.moveLayer(layerId)
  }
}
