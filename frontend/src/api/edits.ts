import {
  useMutation,
  useQueryClient,
  type QueryClient,
  type UseMutationOptions,
} from '@tanstack/react-query'
import { api } from './client'
import { layerKeys, type LayerDetail, type LayerSummary } from './layers'
import { LIST_ONLY } from './projects'

export interface EditCreate {
  /** The negative placeholder; comes back in `createdFids` mapped to the real fid. */
  clientId: number
  geometry: GeoJSON.Geometry
  properties?: Record<string, unknown>
}

export interface EditUpdate {
  fid: number
  /** xmin the draft was based on; omitting it skips the conflict check. */
  rowVersion?: string
  /** Omitted means "attributes only". */
  geometry?: GeoJSON.Geometry
  /** Omitted means "geometry only". */
  properties?: Record<string, unknown>
}

export interface EditRequest {
  creates?: EditCreate[]
  updates?: EditUpdate[]
  deletes?: number[]
  /** Only ever set by an explicit user action -- repairing changes the drawn shape. */
  repairInvalid?: boolean
}

export interface EditResponse {
  createdFids: Record<number, number>
  updated: number
  deleted: number
  /** New tile cache buster; the map rebuilds its tile URLs from it. */
  dataVersion: number
  featureCount: number
}

/**
 * What a write to a layer's rows makes stale, in one place.
 *
 * Every endpoint that changes features owes the same four invalidations. Shared rather
 * than copied: the `LIST_ONLY` reasoning below is exactly the kind of decision that goes
 * wrong when a second caller re-derives it.
 *
 * Split and merge of section 12 use `applyFeatureWriteResult` instead -- their answers
 * carry the two numbers the layer catalog needs, so for them the first two invalidations
 * would be a question already answered.
 */
export function invalidateAfterFeatureWrite(
  queryClient: QueryClient,
  layerId: string,
  projectId: string,
): void {
  queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
  queryClient.invalidateQueries({ queryKey: layerKeys.detail(layerId) })
  invalidateFeatureData(queryClient, layerId)
}

/**
 * What no write can answer for itself: the rows it touched, and the project's own totals.
 *
 * Split out so `applyFeatureWriteResult` can reuse it without dragging the layer catalog
 * along -- that part its response already covers.
 */
function invalidateFeatureData(queryClient: QueryClient, layerId: string): void {
  // Feature pages and single features are both stale now; the key prefix covers both.
  queryClient.invalidateQueries({ queryKey: ['layers', layerId, 'features'] })
  // A heatmap's weight field range (`layerKeys.classify`, `styling/classification.ts`'s
  // `heatmapFieldRangeQuery`) is stale too -- a write can move a field's min or max. The
  // key prefix covers every `field`/`method`/`classes` combination cached for this layer,
  // same convention as the `'features'` prefix just above.
  //
  // Keep this even though `invalidateAfterFeatureWrite`'s own `layerKeys.detail(layerId)`
  // call above -- `['layers', layerId]` -- already matches this prefix under TanStack
  // Query's partial-key comparison, making this line individually redundant *there*
  // (confirmed by mutation testing: deleting it left every test through that path green).
  // It is not redundant through `applyFeatureWriteResult` below, which only ever
  // `setQueryData`s `layerKeys.detail` -- a write, not an invalidation, so nothing else on
  // that path would mark this stale at all. And even on the `invalidateAfterFeatureWrite`
  // path, today's redundancy rests entirely on `layerKeys.detail` happening to still be
  // `['layers', layerId]` -- reshape that key for any unrelated reason (`['layers',
  // layerId, 'detail']`, say) and the coincidental coverage breaks silently, with no test
  // anywhere positioned to notice, since nothing asserts the *coupling*, only its current
  // effect. This line is what keeps the field range correct regardless of that key ever
  // changing shape -- not a leftover to prune as "obviously covered already" (team review,
  // package 2 addendum: found by the Prüfer's own re-check of the mutation result above).
  queryClient.invalidateQueries({ queryKey: ['layers', layerId, 'classify'] })
  // A categorized renderer's distinct-value list (`layerKeys.values`,
  // `styling/classification.ts`'s `layerValuesQuery`) is stale for the same reason: a
  // write can introduce a value that never appeared before. The map does not lie about
  // it in the meantime -- `matchExpression` sends any value with no matching category
  // visibly to `fallbackSymbol` -- but the editor panel's own category list would go on
  // missing it. Same prefix convention as `classify` just above.
  //
  // Same redundancy shape as `classify`, and the same reason to keep it anyway: through
  // `invalidateAfterFeatureWrite`, `layerKeys.detail(layerId)` above already covers this
  // prefix, so this line is individually redundant *there* -- but not through
  // `applyFeatureWriteResult` below, which only `setQueryData`s `layerKeys.detail`
  // rather than invalidating it. See the `classify` comment above for why the
  // "redundant today" half is not a reason to leave this line out.
  queryClient.invalidateQueries({ queryKey: ['layers', layerId, 'values'] })
  // The browser's feature count and extent, and nothing else about the project.
  // `projectKeys.all` would have covered the open project's own detail and its
  // working state too: the detail refetches with `?open=true`, which stamps a fresh
  // `lastOpenedAt` and reorders the project list; an optimistic `basemap` value can
  // fall back to the server's older answer; and the working state reloads between two
  // of its own deferred writes. `LIST_ONLY` carries the reasoning in full.
  queryClient.invalidateQueries(LIST_ONLY)
}

/** The two numbers a feature write hands back about the layer as a whole. */
export interface FeatureWriteResult {
  /** New tile cache buster; the map rebuilds its tile URLs from it. */
  dataVersion: number
  featureCount: number
}

/**
 * Puts the numbers the server just computed straight into the layer catalog, instead of
 * asking for them again.
 *
 * CONTRACT.md 12.3 makes the point: the write recounts `featureCount` and bumps
 * `data_version` anyway, and both travel back in the response, so re-reading the catalog
 * would be a request for an answer already in hand. Written rather than invalidated, the
 * layer tree shows the new count and the map rebuilds its tile URLs in the same frame the
 * response arrives, with no gap in which either still shows the old value.
 *
 * Nothing else about the layer moves: a split's parts cover exactly the shape they came
 * from, and a merge's union covers exactly its parts, so the extent is the same either
 * way. The layer's rows are another matter -- those are invalidated as usual.
 *
 * Only the layer that was written to is touched, and only if the catalog is loaded at
 * all: `setQueryData` with an updater returning `undefined` sets nothing, so a layer list
 * nobody has asked for stays unloaded rather than springing into existence.
 */
export function applyFeatureWriteResult(
  queryClient: QueryClient,
  layerId: string,
  projectId: string,
  { dataVersion, featureCount }: FeatureWriteResult,
): void {
  queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (layers) =>
    layers?.map((layer) =>
      layer.id === layerId ? { ...layer, dataVersion, featureCount } : layer,
    ),
  )
  queryClient.setQueryData<LayerDetail>(layerKeys.detail(layerId), (detail) =>
    detail ? { ...detail, dataVersion, featureCount } : detail,
  )
  invalidateFeatureData(queryClient, layerId)
}

/**
 * Sends the whole edit buffer as one request.
 *
 * On success everything the layer said about itself has changed -- feature count, extent
 * and above all `dataVersion`, which the tile URL is built from. Invalidating the layer
 * list is therefore what actually makes the edit appear on the map.
 *
 * The options are separated from the hook so which queries an edit marks stale can be
 * exercised against a real QueryClient without a React tree, the same reasoning
 * `projectUpdateOptions` gives in `api/projects.ts`.
 */
export function applyEditsOptions(
  queryClient: QueryClient,
  layerId: string,
  projectId: string,
): UseMutationOptions<EditResponse, Error, EditRequest> {
  return {
    mutationFn: (request: EditRequest) =>
      api.post<EditResponse>(`/api/layers/${layerId}/edits`, request),
    onSuccess: () => invalidateAfterFeatureWrite(queryClient, layerId, projectId),
  }
}

export function useApplyEdits(layerId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation(applyEditsOptions(queryClient, layerId, projectId))
}
