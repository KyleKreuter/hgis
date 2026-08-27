import { queryOptions, useQuery, type QueryClient } from '@tanstack/react-query'
import type { RasterLayerSpecification } from 'maplibre-gl'
import { api } from './client'
import type { AttributionPart } from '@/map/basemap'

/**
 * Where a catalog entry is shown in the picker (VERTRAG.md). Kept as `string`, not a
 * union of the six values the contract names today: a group the server adds later must
 * still render as its own section, not fail a type nobody rebuilt for it. `groupBasemaps`
 * groups purely by this string and never hardcodes the names.
 */
export type BasemapGroupName = string

/**
 * What a coverage hint in the picker is about -- nothing technical (VERTRAG.md).
 * `"world"`, `"EU"`, `"DE"`, or a Land's ISO 3166-2:DE code without the `DE-` prefix
 * (`"BY"`, `"NW"`, ...) -- kept as `string`, not a closed union of those sixteen plus
 * three, so a coverage value the picker does not have a German name for yet still
 * renders (`coverageHint`'s own fallback) instead of failing a type nobody rebuilt.
 */
export type BasemapCoverage = string

/**
 * One entry of `GET /api/basemaps` (VERTRAG.md). The backend is the one source of truth
 * for the catalog now -- this type only describes the wire shape, the same as every other
 * `*Response`/`*Summary` type in this folder.
 */
export interface BasemapCatalogEntry {
  /** kebab-case, stable. `osm`, `osm-light`, `osm-dark`, `opentopo`, `none` are the five
   * ids that predate the catalog and must keep working for existing projects. */
  id: string
  title: string
  /** One line under the title; explains what the entry actually is. */
  hint: string
  group: BasemapGroupName
  /** XYZ or WMTS-KVP tile URL with `{z}`, `{x}`, `{y}`. Null only for "none". */
  urlTemplate: string | null
  attribution: readonly AttributionPart[]
  minZoom: number
  maxZoom: number
  coverage: BasemapCoverage
  /** True for the nine Esri layers: ArcGIS's own terms ask for an account, not
   * anything this app enforces -- the picker only has to say so. */
  requiresAccount: boolean
  /** True once the provider has announced the service will be retired. Nobody sets it
   * today; the field exists for the next time somebody does. */
  deprecated: boolean
  /** MapLibre raster paint, e.g. the saturation/brightness/contrast that turn OSM's own
   * tiles into "Hell" and "Dunkel" (see `map/basemap.ts`). Null when the tiles are drawn
   * as delivered. */
  paint: RasterLayerSpecification['paint'] | null
}

interface BasemapsResponse {
  basemaps: BasemapCatalogEntry[]
}

export const basemapKeys = {
  all: ['basemaps'] as const,
}

/**
 * The catalog never changes while the app is running (VERTRAG.md) -- it is the server's
 * own fixed list, not anything a user here edits -- so `staleTime: Infinity` turns every
 * later read into a cache hit instead of the default 30s window's repeat fetches.
 */
export const basemapsQuery = () =>
  queryOptions({
    queryKey: basemapKeys.all,
    queryFn: () => api.get<BasemapsResponse>('/api/basemaps').then((response) => response.basemaps),
    staleTime: Infinity,
  })

export function useBasemaps() {
  return useQuery(basemapsQuery())
}

/**
 * Used by the route loaders (`routes/index.tsx`, `routes/projects.$projectId.tsx`) so
 * that a component needing the catalog synchronously on its very first render --
 * `MapCanvas` resolves a basemap before the `Map` constructor ever runs -- never mounts
 * ahead of it.
 */
export function ensureBasemapsLoaded(queryClient: QueryClient) {
  return queryClient.ensureQueryData(basemapsQuery())
}
