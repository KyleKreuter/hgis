import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { Job } from './imports'

/**
 * One of the three service kinds the catalog carries (CONTRACT.md 11.2). Phase 23 can
 * import `FEATURES` and `BOTH` -- a `WMS` entry has no objects to fetch until the image
 * path (stage 2) exists.
 */
export type GeoportalDatasetKind = 'FEATURES' | 'WMS' | 'BOTH'

export interface GeoportalDatasetSummary {
  /** Opaque, built by the backend -- never parsed here, only handed back in later calls. */
  id: string
  title: string
  description: string | null
  kind: GeoportalDatasetKind
  agency: string | null
  topic: string | null
  featureCount: number | null
  /** [minLng, minLat, maxLng, maxLat] in EPSG:4326, or null when the upstream catalog carries none. */
  bbox: [number, number, number, number] | null
}

/** `GET /api/geoportal/datasets` and the answer to a refresh -- same shape (CONTRACT.md 11.2/11.3). */
export interface GeoportalCatalog {
  fetchedAt: string
  datasets: GeoportalDatasetSummary[]
}

export interface GeoportalField {
  /** Technical name -- what `fields` on the import call and the search filter accept. */
  name: string
  /** Display name, resolved server-side per decision E1. */
  title: string
  /** PostgreSQL target type, same vocabulary as `InspectedField.dataType` on the file import. */
  dataType: string
  /** Short value list from the schema's `enum`, capped at 20 upstream; empty, never null. */
  values: string[]
}

export interface GeoportalDatasetDetail extends GeoportalDatasetSummary {
  attribution: string
  licenseName: string
  licenseUrl: string
  datasetUri: string | null
  metadataUrl: string | null
  storageSrid: number
  /** The field carrying `x-ogc-role: id`, null when the service names none (decision E6). */
  sourceFeatureIdField: string | null
  fields: GeoportalField[]
}

export interface GeoportalCount {
  /** Null when the service will not say. */
  featureCount: number | null
}

export const geoportalKeys = {
  catalog: ['geoportal', 'datasets'] as const,
  detail: (id: string) => ['geoportal', 'datasets', id] as const,
  count: (id: string, bbox: readonly [number, number, number, number]) =>
    ['geoportal', 'datasets', id, 'count', ...bbox] as const,
}

/**
 * The whole catalog (CONTRACT.md 11.2), fetched once and reused. `staleTime: Infinity`
 * is what makes decision E5 ("nur auf Knopfdruck") hold on the client too -- without it,
 * reopening the dialog after the query cache's default 30s staleness would refetch on
 * its own, which is exactly the background schedule E5 rules out.
 */
export const geoportalCatalogQuery = () =>
  queryOptions({
    queryKey: geoportalKeys.catalog,
    queryFn: () => api.get<GeoportalCatalog>('/api/geoportal/datasets'),
    staleTime: Infinity,
  })

export function useGeoportalCatalog() {
  return useQuery(geoportalCatalogQuery())
}

/** The refresh button (CONTRACT.md 11.3): re-fetches upstream and replaces the held copy. */
export function useRefreshGeoportalCatalog() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<GeoportalCatalog>('/api/geoportal/catalog/refresh', {}),
    onSuccess: (catalog) => {
      queryClient.setQueryData(geoportalKeys.catalog, catalog)
    },
  })
}

/**
 * Details for one dataset (CONTRACT.md 11.4) -- the expensive call, fetched from the
 * service on demand. Fires on selection, never for the whole list up front.
 */
export function useGeoportalDataset(id: string | null) {
  return useQuery({
    queryKey: geoportalKeys.detail(id ?? 'none'),
    queryFn: () => api.get<GeoportalDatasetDetail>(`/api/geoportal/datasets/${id}`),
    enabled: id !== null,
  })
}

/**
 * How many features survive a bbox (CONTRACT.md 11.5) -- what the "aktueller
 * Kartenausschnitt" toggle shows before anything is fetched. Only runs once both a
 * dataset and a bbox are known; the unfiltered count already came with 11.4.
 */
export function useGeoportalCount(id: string | null, bbox: [number, number, number, number] | null) {
  return useQuery({
    queryKey: geoportalKeys.count(id ?? 'none', bbox ?? [0, 0, 0, 0]),
    queryFn: () => api.get<GeoportalCount>(`/api/geoportal/datasets/${id}/count?bbox=${bbox!.join(',')}`),
    enabled: id !== null && bbox !== null,
  })
}

export interface StartGeoportalImportInput {
  datasetId: string
  /** Falls back to the dataset title server-side when omitted. */
  name?: string
  /** Absent means the whole dataset. */
  bbox?: [number, number, number, number]
  /** Technical names; absent means every field (decision E2 pre-checks all of them). */
  fields?: string[]
}

/**
 * Starts a Geoportal import (CONTRACT.md 11.6). Answers with the same `Job` shape the
 * file import does -- `useJob` from `api/imports.ts` drives the exact same progress bar
 * without any change on that side, and `useRefreshAfterImport` is reused for the same
 * reason once the job succeeds.
 */
export function useStartGeoportalImport(projectId: string) {
  return useMutation({
    mutationFn: (input: StartGeoportalImportInput) =>
      api.post<Job>(`/api/projects/${projectId}/geoportal-imports`, input),
  })
}
