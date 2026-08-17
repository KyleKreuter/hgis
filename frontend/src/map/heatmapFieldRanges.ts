import { useEffect, useRef } from 'react'
import { useQueries } from '@tanstack/react-query'
import { toast } from 'sonner'
import { isVectorLayer, type LayerSummary } from '@/api/layers'
import { heatmapFieldRangeQuery, resolveRangeState, type FieldRangeState } from '@/styling/classification'

interface HeatmapRangeTarget {
  layerId: string
  /** For the error toast (`useHeatmapRangeErrorToasts`) -- a raw column name would name
   *  nothing a user recognises, the layer's own display name does. */
  layerName: string
  field: string
}

/**
 * Which layers, of the ones the project currently lists, need a heatmap field-range
 * query -- a heatmap renderer with a field chosen. Density-mode heatmaps (no field) and
 * every other renderer need none. Split out of `useHeatmapFieldRanges` so the selection
 * itself is testable without mounting `useQueries`.
 */
export function heatmapRangeTargets(layers: LayerSummary[]): HeatmapRangeTarget[] {
  return layers.flatMap((layer) => {
    if (!isVectorLayer(layer) || !layer.style || layer.style.renderer.type !== 'heatmap') return []
    const field = layer.style.renderer.field
    return field ? [{ layerId: layer.id, layerName: layer.name, field }] : []
  })
}

interface HeatmapRangeState extends HeatmapRangeTarget {
  state: FieldRangeState
}

/**
 * Which layers, of the ones just resolved, just transitioned *into* `'error'` -- pure, so
 * the toast trigger (`useHeatmapRangeErrorToasts`) is testable without mounting a hook,
 * which this project's `environment: 'node'` vitest run cannot do (no jsdom/RTL, same
 * reason `GraduatedEditor`/`CategorizedEditor` are only tested through their pure helpers).
 * A layer already in `previouslyFailed` does not fire again -- that is what keeps the
 * toast to one per failure instead of one per render.
 */
export function layersEnteringError(
  states: Pick<HeatmapRangeState, 'layerId' | 'state'>[],
  previouslyFailed: ReadonlySet<string>,
): string[] {
  return states.filter((entry) => entry.state === 'error' && !previouslyFailed.has(entry.layerId)).map((entry) => entry.layerId)
}

/**
 * Tells the user once a heatmap's field range is confirmed unavailable -- the visual
 * fallback on the map (`styleToMapLibre`'s diagnostic colour) works without anyone
 * having the panel open, but nobody reads a map that quietly changed its own colours as
 * "something failed", and a toast that fired on every re-render would just be noise. So
 * this fires exactly once per layer, on the transition into `'error'` (`layersEnteringError`
 * above), and again the next time it happens after a recovery -- tracked in
 * `previousErrorsRef` rather than in component state, since a Set that drives no render
 * has no business being state.
 */
function useHeatmapRangeErrorToasts(states: HeatmapRangeState[]): void {
  const previousErrorsRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    for (const layerId of layersEnteringError(states, previousErrorsRef.current)) {
      const entry = states.find((candidate) => candidate.layerId === layerId)
      if (!entry) continue
      toast.error(
        `Das Programm konnte die Wertespanne von „${entry.field}" nicht laden. „${entry.layerName}" zeigt deshalb nur die Dichte. Prüfen Sie das Feld, oder laden Sie die Seite neu.`,
      )
    }
    previousErrorsRef.current = new Set(states.filter((entry) => entry.state === 'error').map((entry) => entry.layerId))
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
 * has to run again anyway, to pick the freshly loaded range (or its failure) up.
 */
export function useHeatmapFieldRanges(layers: LayerSummary[]): Map<string, FieldRangeState> {
  const targets = heatmapRangeTargets(layers)
  const results = useQueries({
    queries: targets.map(({ layerId, field }) => heatmapFieldRangeQuery(layerId, field)),
  })
  const states = targets.map((target, index) => ({ ...target, state: resolveRangeState(results[index]) }))

  useHeatmapRangeErrorToasts(states)

  // Still-loading targets are left out entirely rather than written in as `undefined`:
  // a missing key and an explicit `undefined` value read the same way through `.get()`,
  // so there is nothing to gain from carrying the pending ones along.
  return new Map(states.flatMap(({ layerId, state }) => (state === undefined ? [] : [[layerId, state] as const])))
}
