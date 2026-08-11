import {
  queryOptions,
  useMutation,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import type { LayerStyle } from '@/styling/types'
import { api } from './client'
import { projectKeys } from './projects'

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
  /**
   * Symbology, or null for the default monochrome rendering (plan section C).
   *
   * Part of the summary, not just the detail: `MapLayerSync` diffs the map against the
   * layer *list*, so a style that only came with the detail would cost one request per
   * layer before the map could draw anything. Optional in the type because an older
   * server simply omits the field, which then reads as "no style" like it should.
   */
  style?: LayerStyle | null
}

export interface LayerField {
  id: string
  sourceName: string
  columnName: string
  dataType: string
}

export interface LayerDetail extends LayerSummary {
  fields: LayerField[]
  createdAt: string
  updatedAt: string
}

/**
 * What depends on one field -- fetched right before the delete confirmation is shown
 * (contract "Attributfelder löschen"). Without these numbers that confirmation would be
 * an empty formality: the count is the only thing that says what is actually at stake,
 * and the two flags are what warn that deleting also resets part of the style.
 */
export interface LayerFieldUsage {
  /** Objects with a non-NULL value in this field. */
  valueCount: number
  /** The renderer classifies by this field. */
  usedByRenderer: boolean
  /** The active label uses this field. */
  usedByLabels: boolean
}

/**
 * Field types accepted when creating a layer from scratch (contract "Layer selbst
 * anlegen"). `uuid` and `bytea` are deliberately absent from this list -- both are
 * read-only in the attribute editor (phase 9), so offering them here would create a
 * field that can never be filled in.
 */
export type FieldType =
  | 'TEXT'
  | 'INTEGER'
  | 'BIGINT'
  | 'DOUBLE'
  | 'NUMERIC'
  | 'BOOLEAN'
  | 'DATE'
  | 'TIME'
  | 'TIMESTAMP'

/**
 * Geometry kinds the create-layer endpoint accepts. `GEOMETRY` (mixed) is excluded on
 * purpose: it needs three separate MapLibre layers on one source and is not a
 * meaningful choice for a layer that starts out empty (CONTRACT.md).
 */
export type CreatableGeometryType = Exclude<GeometryType, 'GEOMETRY'>

export interface CreateLayerField {
  name: string
  type: FieldType
}

export interface CreateLayerInput {
  name: string
  geometryType: CreatableGeometryType
  fields: CreateLayerField[]
}

export interface UpdateLayerInput {
  name?: string
  visible?: boolean
  zIndex?: number
  minZoom?: number
  maxZoom?: number
  /** `null` resets the layer to the default rendering. */
  style?: LayerStyle | null
}

export type ClassifyMethod = 'quantile' | 'equalInterval' | 'naturalBreaks'

export interface ClassifyResult {
  field: string
  method: ClassifyMethod
  /** n+1 values: the lower bound of every class, plus the maximum. */
  breaks: number[]
  min: number
  max: number
  /** Objects without a value -- they fall to the fallback symbol, not into a class. */
  nullCount: number
}

export interface FieldValue {
  value: string | number | null
  count: number
}

export interface FieldValuesResult {
  field: string
  /** Descending by frequency. */
  values: FieldValue[]
  /** True when the column has more distinct values than were asked for. */
  truncated: boolean
}

export const layerKeys = {
  /** Layer list of one project -- what `MapLayerSync` diffs against the map. */
  list: (projectId: string) => ['projects', projectId, 'layers'] as const,
  detail: (layerId: string) => ['layers', layerId] as const,
  fieldUsage: (layerId: string, fieldId: string) =>
    ['layers', layerId, 'fields', fieldId, 'usage'] as const,
  values: (layerId: string, field: string) => ['layers', layerId, 'values', field] as const,
  classify: (layerId: string, field: string, method: ClassifyMethod, classes: number) =>
    ['layers', layerId, 'classify', field, method, classes] as const,
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

export const layerFieldUsageQuery = (layerId: string, fieldId: string) =>
  queryOptions({
    queryKey: layerKeys.fieldUsage(layerId, fieldId),
    queryFn: () =>
      api.get<LayerFieldUsage>(`/api/layers/${layerId}/fields/${fieldId}/usage`),
  })

/**
 * Distinct values of one column, for the categorized renderer.
 *
 * `field` is the source name, the same one the field list shows -- the server resolves
 * it to a column; an identifier never travels from here into a query.
 */
export const layerValuesQuery = (layerId: string, field: string, limit = 100) =>
  queryOptions({
    queryKey: layerKeys.values(layerId, field),
    queryFn: () =>
      api.get<FieldValuesResult>(
        `/api/layers/${layerId}/values?field=${encodeURIComponent(field)}&limit=${limit}`,
      ),
    // The column does not change while the panel is open, and re-reading it would cost a
    // full scan on a large layer.
    staleTime: 5 * 60 * 1000,
  })

/** Class boundaries for the graduated renderer. `classes` is 2..12 (contract). */
export const layerClassifyQuery = (
  layerId: string,
  field: string,
  method: ClassifyMethod,
  classes: number,
) =>
  queryOptions({
    queryKey: layerKeys.classify(layerId, field, method, classes),
    queryFn: () =>
      api.get<ClassifyResult>(
        `/api/layers/${layerId}/classify?field=${encodeURIComponent(field)}&method=${method}&classes=${classes}`,
      ),
    staleTime: 5 * 60 * 1000,
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

/**
 * Writes the symbology. Separate from `useUpdateLayer` because its cache rules are
 * the opposite ones.
 *
 * The list cache already carries what the user sees -- the symbology panel writes every
 * change into it so the map follows the colour picker without waiting for a round trip.
 * So the response must NOT put its `style` back: while a debounced request is in flight
 * the user has usually moved on, and the answer to the older request would drag the map
 * back for one frame. Everything else from the response is taken, `styleVersion` above
 * all: that one decides whether the tiles have to be fetched again.
 */
export function useUpdateLayerStyle(layerId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (style: LayerStyle | null) =>
      api.patch<LayerDetail>(`/api/layers/${layerId}`, { style }),
    onSuccess: (updated) => {
      queryClient.setQueryData(layerKeys.detail(layerId), updated)
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) =>
        current?.map((layer) =>
          layer.id === layerId ? { ...layer, ...updated, style: layer.style } : layer,
        ),
      )
    },
    // A rejected style means the cache holds something the server does not have; only a
    // refetch can say what is actually stored.
    onError: () => {
      queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
      queryClient.invalidateQueries({ queryKey: layerKeys.detail(layerId) })
    },
  })
}

export interface AddLayerFieldInput {
  name: string
  type: FieldType
}

/**
 * Adds an attribute field to an existing layer (contract "Attributfelder hinzufügen").
 * Existing objects get `NULL` in the new column, so every already-fetched feature row
 * is out of date the moment this succeeds -- unlike a rename, which only relabels, this
 * has to drop the feature caches too, not just the layer's own metadata.
 */
export function useAddLayerField(layerId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: AddLayerFieldInput) =>
      api.post<LayerField>(`/api/layers/${layerId}/fields`, input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: layerKeys.detail(layerId) })
      queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
      // New column on every row -- feature pages and single features are both stale
      // now, the key prefix covers both (same trick useApplyEdits uses in api/edits.ts).
      queryClient.invalidateQueries({ queryKey: ['layers', layerId, 'features'] })
    },
  })
}

export interface RenameLayerFieldInput {
  fieldId: string
  name: string
}

/**
 * Renames a field's display name (contract "Attributfelder umbenennen"). `columnName`
 * and `dataType` never change, so every feature row already in the cache is still
 * correct as it stands -- only the layer's own field list (source of the label) is out
 * of date.
 */
export function useRenameLayerField(layerId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ fieldId, name }: RenameLayerFieldInput) =>
      api.patch<LayerField>(`/api/layers/${layerId}/fields/${fieldId}`, { name }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: layerKeys.detail(layerId) })
      queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
    },
  })
}

/**
 * Deletes a field irreversibly (contract "Attributfelder löschen"). The backend drops
 * the column and, if the renderer or labels pointed at it, resets that part of the
 * style too -- all in one transaction -- so both the detail cache (carries the style)
 * and the list cache (carries `styleVersion`) are stale, on top of every feature row
 * losing a column, same as `useAddLayerField`.
 */
export function useDeleteLayerField(layerId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (fieldId: string) => api.delete<void>(`/api/layers/${layerId}/fields/${fieldId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: layerKeys.detail(layerId) })
      queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
      // The column is gone from every row now, same trick useAddLayerField uses.
      queryClient.invalidateQueries({ queryKey: ['layers', layerId, 'features'] })
    },
  })
}

/**
 * Creates an empty layer that can be drawn into right away (contract "Layer selbst
 * anlegen"). The response is the same `LayerSummary` shape the list endpoint returns
 * per entry, so it is appended to the cached list directly instead of waiting on a
 * refetch -- the same trick `useUpdateLayer` uses for a patch.
 */
export function useCreateLayer(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateLayerInput) =>
      api.post<LayerSummary>(`/api/projects/${projectId}/layers`, input),
    onSuccess: (created) => {
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) =>
        current ? [...current, created] : [created],
      )
      queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
      // The project browser shows feature and layer totals per project (like after an
      // import, see useRefreshAfterImport in api/imports.ts).
      queryClient.invalidateQueries({ queryKey: projectKeys.all })
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
