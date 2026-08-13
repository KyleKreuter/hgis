import {
  useMutation,
  useQueryClient,
  type QueryClient,
  type UseMutationOptions,
} from '@tanstack/react-query'
import { api } from './client'
import { layerKeys } from './layers'
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
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
      queryClient.invalidateQueries({ queryKey: layerKeys.detail(layerId) })
      // Feature pages and single features are both stale now; the key prefix covers both.
      queryClient.invalidateQueries({ queryKey: ['layers', layerId, 'features'] })
      // The browser's feature count and extent, and nothing else about the project.
      // `projectKeys.all` would have covered the open project's own detail and its
      // working state too: the detail refetches with `?open=true`, which stamps a fresh
      // `lastOpenedAt` and reorders the project list; an optimistic `basemap` value can
      // fall back to the server's older answer; and the working state reloads between two
      // of its own deferred writes. `LIST_ONLY` carries the reasoning in full.
      queryClient.invalidateQueries(LIST_ONLY)
    },
  }
}

export function useApplyEdits(layerId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation(applyEditsOptions(queryClient, layerId, projectId))
}
