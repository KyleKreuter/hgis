import { useQueries } from '@tanstack/react-query'
import { isVectorLayer, type LayerSummary } from '@/api/layers'
import { heatmapFieldRangeQuery, type FieldRange } from '@/styling/classification'

/**
 * Which layers, of the ones the project currently lists, need a heatmap field-range
 * query -- a heatmap renderer with a field chosen. Density-mode heatmaps (no field) and
 * every other renderer need none. Split out of `useHeatmapFieldRanges` so the selection
 * itself is testable without mounting `useQueries`.
 */
export function heatmapRangeTargets(layers: LayerSummary[]): { layerId: string; field: string }[] {
  return layers.flatMap((layer) => {
    if (!isVectorLayer(layer) || !layer.style || layer.style.renderer.type !== 'heatmap') return []
    const field = layer.style.renderer.field
    return field ? [{ layerId: layer.id, field }] : []
  })
}

/**
 * Field ranges for every heatmap layer currently on the map, keyed by layer id -- what
 * `syncMapLayers` needs to normalise `heatmap-weight` (`styleToMapLibre`'s `fieldRange`
 * parameter). A dynamic list of queries, one per heatmap layer with a field, sharing
 * `heatmapFieldRangeQuery`'s cache with `HeatmapEditor`'s own legend so the range is
 * fetched once, not once per consumer.
 *
 * Rebuilt on every call rather than memoised: `MapLayerSync` (the only caller) only
 * re-renders when its own inputs change -- the layer list, or one of these very range
 * queries settling -- so there is no unrelated render this would otherwise run
 * needlessly against, and a settled query is exactly the case where `syncMapLayers`
 * has to run again anyway, to pick the freshly loaded range up.
 */
export function useHeatmapFieldRanges(layers: LayerSummary[]): Map<string, FieldRange> {
  const targets = heatmapRangeTargets(layers)
  const results = useQueries({
    queries: targets.map(({ layerId, field }) => heatmapFieldRangeQuery(layerId, field)),
  })

  return new Map(
    targets.flatMap(({ layerId }, index) => {
      const data = results[index]?.data
      return data ? [[layerId, { min: data.min, max: data.max }] as const] : []
    }),
  )
}
