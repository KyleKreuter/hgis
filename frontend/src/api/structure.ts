import {
  useMutation,
  useQueryClient,
  type QueryClient,
  type UseMutationOptions,
} from '@tanstack/react-query'
import { api } from './client'
import { invalidateAfterFeatureWrite } from './edits'

/**
 * The two structural operations of CONTRACT.md section 12: cutting one feature in two,
 * and folding several into one.
 *
 * Both write immediately instead of joining the edit batch of section 10. PostGIS
 * computes the result, so it does not exist until the server has produced it, and a
 * local buffer would be stale the moment it did -- which is why the client has to
 * require an empty buffer before either is offered at all.
 *
 * Neither is undoable. The edit buffer's undo stack cannot reach past a write that has
 * already happened, the same deal a delete makes.
 */

/** CONTRACT.md 12.2: below two there is nothing to merge, above a hundred the server refuses. */
export const MERGE_MIN_FEATURES = 2
export const MERGE_MAX_FEATURES = 100

export interface SplitRequest {
  /** The cut, GeoJSON in EPSG:4326 -- like every other geometry on the wire. */
  line: GeoJSON.LineString
  /** xmin of the row the cut was planned against. A mismatch is a 409, and nothing is written. */
  rowVersion: string
}

/** The request plus the feature it addresses, which goes into the path rather than the body. */
export interface SplitVariables extends SplitRequest {
  fid: number
}

export interface SplitResponse {
  /**
   * The original fid first, then one per further part. The original survives, so a
   * selection or an open attribute form holding it stays valid.
   */
  fids: number[]
  dataVersion: number
}

export interface MergeRequest {
  fids: number[]
  /**
   * Whose attributes the result keeps. Always one of `fids` -- the user picked it, and
   * the order of a selection is not a decision.
   */
  leadFid: number
  /** fid as a string key -> xmin. Every part carries one; one mismatch rolls back all of it. */
  rowVersions: Record<string, string>
}

export interface MergeResponse {
  /** The lead's fid, kept. Every other part is gone. */
  fid: number
  dataVersion: number
}

/**
 * Cuts one feature along a drawn line.
 *
 * Options apart from the hook so the invalidation can be exercised against a real
 * QueryClient without a React tree -- same reasoning as `applyEditsOptions`.
 */
export function splitFeatureOptions(
  queryClient: QueryClient,
  layerId: string,
  projectId: string,
): UseMutationOptions<SplitResponse, Error, SplitVariables> {
  return {
    mutationFn: ({ fid, ...request }: SplitVariables) =>
      api.post<SplitResponse>(`/api/layers/${layerId}/features/${fid}/split`, request),
    // The same four invalidations an edit batch owes: the parts do not exist in any
    // cached page yet, and the tile URL is built from the layer list's `dataVersion`.
    onSuccess: () => invalidateAfterFeatureWrite(queryClient, layerId, projectId),
  }
}

export function useSplitFeature(layerId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation(splitFeatureOptions(queryClient, layerId, projectId))
}

/** Folds several features into the lead, which keeps its fid and every attribute value. */
export function mergeFeaturesOptions(
  queryClient: QueryClient,
  layerId: string,
  projectId: string,
): UseMutationOptions<MergeResponse, Error, MergeRequest> {
  return {
    mutationFn: (request: MergeRequest) =>
      api.post<MergeResponse>(`/api/layers/${layerId}/features/merge`, request),
    onSuccess: () => invalidateAfterFeatureWrite(queryClient, layerId, projectId),
  }
}

export function useMergeFeatures(layerId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation(mergeFeaturesOptions(queryClient, layerId, projectId))
}
