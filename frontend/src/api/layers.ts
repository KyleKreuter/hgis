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

/** The four clip modes a mask layer can take (CONTRACT.md phase 21); `null` means no mask. */
export type ClipMode = 'insideWhole' | 'insideClipped' | 'outsideWhole' | 'outsideClipped'

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
  /**
   * The layer's own background map; `null` means it follows the project's (CONTRACT.md
   * phase 18). Part of the summary for the same reason `style` is: the map resolves the
   * basemap for the active layer straight from the layer list, without a detail fetch.
   * Optional in the type for the same reason `style` is: an older server omits it, which
   * then reads as "no override" like it should.
   */
  basemap?: string | null
  /** The layer's own background opacity; `null` or missing means it follows the project's. */
  basemapOpacity?: number | null
  /**
   * This layer's role as a clip mask, or `null`/missing when it holds none (CONTRACT.md
   * phase 21). One of four modes: `'insideWhole'`/`'outsideWhole'` keep an object whole
   * when it does/does not touch this layer's geometry (`ST_Intersects`); `'insideClipped'`/
   * `'outsideClipped'` cut the object down to the part inside/outside it instead. Any
   * number of layers in a project can be a mask at once; each one affects every layer
   * with a higher `zIndex`, and their effects combine with AND -- so a second layer
   * becoming a mask leaves every other mask's `clipMode` untouched, unlike the old "at
   * most one mask per project" rule.
   * Optional for the same reason `style`/`basemap` are: missing reads as "not a mask",
   * which is the right default and keeps every existing fixture that builds a
   * `LayerSummary` without it (outside this phase's ownership) compiling unchanged.
   */
  clipMode?: ClipMode | null
  /**
   * `0` when no mask affects this layer, otherwise a value that changes whenever any
   * mask that affects it, that mask's geometries, or this layer's position relative to
   * one changes -- with several masks in play, all of them fold into this one number.
   * Folded into `buildTileUrl` (`map/layerSpecs.ts`) exactly like `dataVersion` and
   * `styleVersion`, because the tile content now depends on it too -- without it in the
   * URL, an edited mask would leave every clipped layer showing the old cut for as long
   * as the tiles stay cached (they are served `immutable`). Optional for the same
   * reason `clipMode` is; missing reads as `0`, "no clip in effect".
   */
  clipVersion?: number
  /**
   * How the server's current build renders a tile -- the same value for every layer of
   * every project, and the fourth part of the tile address (`buildTileUrl`).
   *
   * The other three versions all follow the data, so none of them moves when the
   * rendering itself changes meaning for data that stayed the same. That is not
   * hypothetical: narrowing "Nur innerhalb" to fully-contained objects (CONTRACT.md
   * phase 21a) left every input to the tile address untouched, so clients kept showing
   * the old cut -- and tiles are served `immutable` with a year's lifetime. The server
   * raises this by hand when that happens. Optional; missing reads as `1`.
   */
  renderVersion?: number
  /**
   * Where this layer's data came from, or `null`/missing for a layer imported from a
   * file or drawn by hand (CONTRACT.md phase 23, section 11.7). Part of the summary, not
   * just the detail, for the same reason `style`/`basemap` are: the map's attribution
   * line (`MapCanvas`) is built from the layer *list*, and a licence notice that only
   * came with the detail would cost one request per visible layer before it could show
   * anything.
   */
  source?: LayerSource | null
}

export interface LayerField {
  id: string
  sourceName: string
  columnName: string
  dataType: string
}

/**
 * Provenance of a layer imported from the Geoportal Hamburg (CONTRACT.md phase 23,
 * section 11.7), `null` for every layer that was not. `datasetId` and `featureIdField`
 * exist for a later stage's reconcile (decision E6) and are deliberately shown nowhere
 * in the UI yet.
 */
export interface LayerSource {
  /** The licence's "Bezeichnung des Bereitstellers", set by the agency itself -- differs
   *  between agencies, so it is stored and shown per layer, never as one fixed text. */
  attribution: string
  licenseName: string
  licenseUrl: string
  datasetUri: string | null
  metadataUrl: string | null
  datasetId: string
  featureIdField: string | null
  fetchedAt: string
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
 * Geometry kinds the create-layer endpoint accepts -- the full {@link GeometryType} set,
 * `GEOMETRY` (mixed) included. Mixed layers are fully supported on the map side
 * (`frontend/src/map/layerSpecs.ts` splits one source into three MapLibre layers by
 * geometry type), so there is nothing left barring an empty layer from starting out
 * mixed too (CONTRACT.md).
 */
export type CreatableGeometryType = GeometryType

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
  /** `null` resets the layer to the project's background map. */
  basemap?: string | null
  /** `null` resets the layer to the project's background opacity. */
  basemapOpacity?: number | null
  /**
   * Sets this layer's clip mode, or clears it with `null`. Every other layer's
   * `clipMode` is left exactly as it was -- a project can hold any number of masks at
   * once (CONTRACT.md phase 21), so marking one layer as a mask no longer demotes
   * another.
   */
  clipMode?: ClipMode | null
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
