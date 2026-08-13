import { queryOptions, useQuery } from '@tanstack/react-query'
import { api } from './client'

/**
 * One layer a WMS service offers, flattened out of its group tree (wms-api-vertrag.md
 * section 2: "Die Liste ist flach, die Schachtelung steckt in depth"). A group with no
 * name of its own is not abrufbar and never appears here -- there is nothing this
 * client could ask for on its behalf.
 */
export interface WmsCapabilityLayer {
  name: string
  title: string
  /** Indentation level for the flat list -- 0 is a top-level layer. */
  depth: number
  queryable: boolean
  legendUrl: string | null
  /** Scale denominators the service itself declares, or null when it names none. */
  minScale: number | null
  maxScale: number | null
  /** [minLng, minLat, maxLng, maxLat] in EPSG:4326, or null. */
  bbox: [number, number, number, number] | null
}

export interface WmsCapabilities {
  /** The bare service address, echoed back without a query string. */
  serviceUrl: string
  title: string
  version: string
  imageFormats: string[]
  layers: WmsCapabilityLayer[]
}

export const wmsKeys = {
  capabilities: (url: string) => ['wms', 'capabilities', url] as const,
}

/**
 * Reads a WMS service's `GetCapabilities` (wms-api-vertrag.md section 2). `url` may
 * come with or without its own query string -- the backend fills in what is missing.
 *
 * The backend rejects a service that cannot serve EPSG:3857 or that is not WMS 1.3.0
 * with a `422` naming the reason, an unreachable one with `502`, and a disallowed
 * address (SSRF guard) with `400` -- all three arrive as `ApiError.message`, ready to
 * show as-is.
 */
export const wmsCapabilitiesQuery = (url: string) =>
  queryOptions({
    queryKey: wmsKeys.capabilities(url),
    queryFn: () => api.get<WmsCapabilities>(`/api/wms/capabilities?url=${encodeURIComponent(url)}`),
    // A capabilities document does not change while a dialog is open, and re-reading
    // it costs the target service a request, not just this one.
    staleTime: 5 * 60 * 1000,
    retry: false,
  })

/** `enabled: false` while `url` is `null` -- e.g. before the user has entered one. */
export function useWmsCapabilities(url: string | null) {
  return useQuery({
    ...wmsCapabilitiesQuery(url ?? ''),
    enabled: url !== null && url.trim() !== '',
  })
}
