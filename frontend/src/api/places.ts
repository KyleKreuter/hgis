import { queryOptions, useQuery } from '@tanstack/react-query'
import { api } from './client'

/**
 * Where a hit came from (CONTRACT.md "Ortssuche"): Hamburg's own PostGIS import, or a
 * live lookup against Photon (OpenStreetMap) for everything outside it. Shown in the
 * result list -- an official Hamburg street and an OSM guess are not the same thing,
 * and hiding which is which would hide exactly the fact a user needs to judge a hit by.
 */
export type PlaceSource = 'hamburg' | 'photon'

/** `street` a road, `district` a Hamburg Ortsteil, `place` everything else Photon returns. */
export type PlaceKind = 'street' | 'district' | 'place'

export interface Place {
  /** The name alone, without whatever disambiguates it -- that lives in `context`. */
  name: string
  /**
   * What tells two same-named hits apart -- Ortsteil and postcode for a Hamburg street,
   * town and country otherwise. Null when the source has nothing to add.
   */
  context: string | null
  /** EPSG:4326, always present regardless of `source`. */
  lng: number
  lat: number
  source: PlaceSource
  kind: PlaceKind
}

interface PlacesResponse {
  places: Place[]
}

/**
 * Below this the endpoint itself refuses with 400 (CONTRACT.md: "mindestens 2 Zeichen
 * nach dem Trimmen"). The client's own threshold is one character higher -- see
 * `MIN_QUERY_LENGTH` -- so this is never actually reached from here; it stays as the
 * hard floor `usePlaceSearch` enforces regardless of what a future caller passes in.
 */
const MIN_SERVER_QUERY_LENGTH = 2

const DEFAULT_LIMIT = 10

export const placeKeys = {
  search: (query: string, limit: number) => ['places', 'search', query, limit] as const,
}

export const placesQuery = (query: string, limit: number = DEFAULT_LIMIT) =>
  queryOptions({
    queryKey: placeKeys.search(query, limit),
    queryFn: () => api.get<PlacesResponse>(`/api/places?q=${encodeURIComponent(query)}&limit=${limit}`),
    // A search box is exploratory by nature -- the same three letters typed again a
    // minute later (backspace, retype) should not force a second round trip while the
    // held data is still exactly what the backend would answer with.
    staleTime: 60 * 1000,
  })

/**
 * Search starts at three characters, one more than the endpoint requires (CONTRACT.md:
 * "ab drei Zeichen (der Endpunkt nimmt ab zwei -- die dritte Stelle spart Anfragen,
 * ohne dass es sich träge anfühlt)"). Two letters alone match far too much to narrow
 * anything down, and a search field that fires on every one of them burns a request the
 * very next keystroke throws away.
 */
export const MIN_QUERY_LENGTH = 3

/**
 * Runs once the trimmed query reaches {@link MIN_QUERY_LENGTH}; disabled otherwise, so a
 * one- or two-character draft never reaches the network -- neither `usePlaceSearch`'s own
 * floor nor a stray call from elsewhere can undercut the endpoint's 400 boundary.
 */
export function usePlaceSearch(query: string) {
  const trimmed = query.trim()
  return useQuery({
    ...placesQuery(trimmed),
    enabled: trimmed.length >= Math.max(MIN_QUERY_LENGTH, MIN_SERVER_QUERY_LENGTH),
  })
}
