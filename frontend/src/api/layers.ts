import {
  queryOptions,
  useMutation,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import { api } from './client'

/**
 * MULTIPOINT | MULTILINESTRING | MULTIPOLYGON for a single geometry kind, GEOMETRY
 * for genuinely mixed sources (contract section 5.1). A GEOMETRY layer needs three
 * MapLibre layers on one source, split by ['==', ['geometry-type'], …] -- see
 * `frontend/src/map/layerSpecs.ts`.
 */
export type GeometryType = 'MULTIPOINT' | 'MULTILINESTRING' | 'MULTIPOLYGON' | 'GEOMETRY'

export interface LayerSummary {
  id: string
  name: string
  geometryType: GeometryType
  srid: number
  featureCount: number
  visible: boolean
  zIndex: number
  minZoom: number
  maxZoom: number
  dataVersion: number
  styleVersion: number
  /** [minLng, minLat, maxLng, maxLat] in EPSG:4326, or null. */
  extent: [number, number, number, number] | null
}

export interface LayerField {
  id: string
  sourceName: string
  columnName: string
  dataType: string
}

export interface LayerDetail extends LayerSummary {
  fields: LayerField[]
  /** Reserved, phase 7. */
  style: unknown | null
  createdAt: string
  updatedAt: string
}

export interface UpdateLayerInput {
  name?: string
  visible?: boolean
  zIndex?: number
  minZoom?: number
  maxZoom?: number
}

export const layerKeys = {
  /** Layer list of one project -- what `MapLayerSync` diffs against the map. */
  list: (projectId: string) => ['projects', projectId, 'layers'] as const,
  detail: (layerId: string) => ['layers', layerId] as const,
}

export const layerListQuery = (projectId: string) =>
  queryOptions({
    queryKey: layerKeys.list(projectId),
    queryFn: () => api.get<LayerSummary[]>(`/api/projects/${projectId}/layers`),
  })

export const layerDetailQuery = (layerId: string) =>
  queryOptions({
    queryKey: layerKeys.detail(layerId),
    queryFn: () => api.get<LayerDetail>(`/api/layers/${layerId}`),
  })

/**
 * @param projectId needed to patch the cached list entry (visibility, zIndex, …)
 *   alongside the detail cache, so the map and a future layer tree stay in sync
 *   without waiting for a refetch.
 */
export function useUpdateLayer(layerId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: UpdateLayerInput) => api.patch<LayerDetail>(`/api/layers/${layerId}`, input),
    // A visibility checkbox that only ticks once the server answered feels broken, and
    // MapLayerSync reads this same cache -- so the map switches with the tick.
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: layerKeys.list(projectId) })
      const previous = queryClient.getQueryData<LayerSummary[]>(layerKeys.list(projectId))
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) =>
        current?.map((layer) => (layer.id === layerId ? { ...layer, ...input } : layer)),
      )
      return { previous }
    },
    onError: (_error, _input, context) => {
      if (context?.previous) {
        queryClient.setQueryData(layerKeys.list(projectId), context.previous)
      }
    },
    onSuccess: (updated) => {
      queryClient.setQueryData(layerKeys.detail(layerId), updated)
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) =>
        current?.map((layer) => (layer.id === layerId ? { ...layer, ...updated } : layer)),
      )
      queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
    },
  })
}

export function useDeleteLayer(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (layerId: string) => api.delete<void>(`/api/layers/${layerId}`),
    onSuccess: (_result, layerId) => {
      queryClient.removeQueries({ queryKey: layerKeys.detail(layerId) })
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) =>
        current?.filter((layer) => layer.id !== layerId),
      )
      queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
    },
  })
}

/**
 * Writes the whole stacking order in one request.
 *
 * The parameter is ordered bottom first, matching the endpoint and `zIndex` itself --
 * the layer tree displays the reverse, because a tree reads top-down. One request for
 * the entire order is what keeps a failure from leaving half the layers moved.
 */
export function useReorderLayers(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (layerIdsBottomToTop: string[]) =>
      api.put<LayerSummary[]>(`/api/projects/${projectId}/layers/order`, { layerIdsBottomToTop }),
    // Without this the row would snap back to its old position for one frame and then
    // jump to the new one -- the drop has to look final immediately.
    onMutate: async (layerIdsBottomToTop) => {
      await queryClient.cancelQueries({ queryKey: layerKeys.list(projectId) })
      const previous = queryClient.getQueryData<LayerSummary[]>(layerKeys.list(projectId))
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) => {
        if (!current) return current
        const byId = new Map(current.map((layer) => [layer.id, layer]))
        return layerIdsBottomToTop.flatMap((id, index) => {
          const layer = byId.get(id)
          return layer ? [{ ...layer, zIndex: index }] : []
        })
      })
      return { previous }
    },
    onError: (_error, _input, context) => {
      if (context?.previous) {
        queryClient.setQueryData(layerKeys.list(projectId), context.previous)
      }
    },
    onSuccess: (ordered) => {
      queryClient.setQueryData(layerKeys.list(projectId), ordered)
    },
  })
}

/** Used by the map route so it never diffs against empty data on first paint. */
export function ensureLayersLoaded(queryClient: QueryClient, projectId: string) {
  return queryClient.ensureQueryData(layerListQuery(projectId))
}
