import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query'
import { api } from './client'

/**
 * Attribute values, keyed by `columnName` -- not by the source name shown in the UI.
 * Source names are not unique (DBF truncates to ten characters, so two attributes can
 * arrive under one name), so the display name cannot be the key.
 */
export type FeatureProperties = Record<string, string | number | boolean | null>

export interface Feature {
  fid: number
  /** PostgreSQL's xmin, carried for the optimistic locking of phase 6. */
  rowVersion: string
  properties: FeatureProperties
  /** GeoJSON geometry in EPSG:4326, only present when the request asked for it. */
  geometry?: { type: string; coordinates: unknown } | null
}

export interface FeaturePage {
  features: Feature[]
  /** Opaque; pass back to fetch the next page. Absent on the last page. */
  nextCursor?: string
  /** Only on the first page of a query -- counting rescans, and the number cannot change. */
  totalCount?: number
}

/**
 * How `bbox` is matched against a feature's geometry -- see CONTRACT.md. Absent means
 * the backend's old, bounding-box-only behaviour; `DrawController`'s snap/edit loads
 * rely on exactly that and must keep leaving `mode` unset.
 */
export type SpatialMode = 'intersects' | 'contains'

export interface FeatureQuery {
  layerId: string
  /** Field to sort by, source name or column name; fid when omitted. */
  sort?: string
  desc?: boolean
  filter?: string
  /**
   * Case-insensitive partial match against every text field of the layer. Combines
   * with `filter` as AND when both are set -- the UI only ever sends one of the two,
   * but the backend accepts both together. See CONTRACT.md.
   */
  search?: string
  /** [minLng, minLat, maxLng, maxLat] in EPSG:4326. */
  bbox?: [number, number, number, number]
  /** Only meaningful together with `bbox`; see `SpatialMode`. */
  mode?: SpatialMode
  geometry?: boolean
  size?: number
}

export const featureKeys = {
  page: (query: FeatureQuery) =>
    [
      'layers',
      query.layerId,
      'features',
      {
        sort: query.sort ?? null,
        desc: query.desc ?? false,
        filter: query.filter ?? null,
        search: query.search ?? null,
      },
    ] as const,
  detail: (layerId: string, fid: number) => ['layers', layerId, 'features', fid] as const,
}

/**
 * Query string shared by every features request -- pages, the fid endpoint and (via
 * `cursor`) the next-page fetch. Exported for its own tests: the trimming and the
 * filter/search combination are exactly the part worth getting right without a network
 * call.
 */
export function buildQueryString(query: FeatureQuery, cursor?: string): string {
  const params = new URLSearchParams()
  if (query.sort) params.set('sort', query.sort)
  if (query.desc) params.set('desc', 'true')
  if (query.filter?.trim()) params.set('filter', query.filter.trim())
  if (query.search?.trim()) params.set('search', query.search.trim())
  if (query.bbox) params.set('bbox', query.bbox.join(','))
  if (query.mode) params.set('mode', query.mode)
  if (query.geometry) params.set('geometry', 'true')
  if (query.size) params.set('size', String(query.size))
  if (cursor) params.set('cursor', cursor)
  return params.toString()
}

/**
 * Pages of a layer's rows.
 *
 * Infinite rather than indexed because the backend pages by keyset: there is no page
 * number to jump to, only "what comes after this position". That matches how a scrolling
 * table reads anyway, and it is what keeps deep scrolling from getting slower.
 */
export const featurePagesQuery = (query: FeatureQuery) =>
  infiniteQueryOptions({
    queryKey: featureKeys.page(query),
    queryFn: ({ pageParam }) =>
      api.get<FeaturePage>(`/api/layers/${query.layerId}/features?${buildQueryString(query, pageParam)}`),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    // The total arrives with the first page only, so it is lifted out of the page list
    // here rather than every consumer knowing where to look for it.
    select: (data) => ({
      rows: data.pages.flatMap((page) => page.features),
      totalCount: data.pages[0]?.totalCount ?? 0,
    }),
    enabled: Boolean(query.layerId),
  })

/**
 * One page of a bbox query, fetched directly rather than through the query cache.
 *
 * Used by the rectangle select tool, which drives its own pagination loop (count check,
 * then page after page until `nextCursor` is empty) and whose results are transient --
 * caching them under `featureKeys.page` would grow the cache by one entry per rectangle
 * ever drawn, for data nothing else reads.
 */
export function fetchFeaturePage(query: FeatureQuery, cursor?: string): Promise<FeaturePage> {
  return api.get<FeaturePage>(`/api/layers/${query.layerId}/features?${buildQueryString(query, cursor)}`)
}

/** One feature with all attributes and its geometry -- what Identify displays. */
export const featureDetailQuery = (layerId: string, fid: number) =>
  queryOptions({
    queryKey: featureKeys.detail(layerId, fid),
    queryFn: () => api.get<Feature>(`/api/layers/${layerId}/features/${fid}?`),
    enabled: Boolean(layerId) && Number.isFinite(fid),
  })

/** The full fid set matching a `filter`/`search` restriction, as returned by the fids endpoint. */
export interface FeatureFids {
  fids: number[]
  totalCount: number
}

/**
 * The complete, unpaged fid set for a `filter`/`search` restriction -- no geometry, no
 * attributes, no cursor loop to walk. What "select all matches" needs before handing
 * the ids to the selection store, which is what makes the existing fid-based export
 * usable for a filtered or searched set (CONTRACT.md).
 *
 * Not cached under `featureKeys`: the result feeds a one-off action (fill the
 * selection), not something a component re-renders from repeatedly.
 */
export function fetchFeatureFids(
  query: Pick<FeatureQuery, 'layerId' | 'filter' | 'search'>,
): Promise<FeatureFids> {
  return api.get<FeatureFids>(`/api/layers/${query.layerId}/features/fids?${buildQueryString(query)}`)
}
