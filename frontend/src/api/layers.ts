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

/** Used by the map route so it never diffs against empty data on first paint. */
export function ensureLayersLoaded(queryClient: QueryClient, projectId: string) {
  return queryClient.ensureQueryData(layerListQuery(projectId))
}
