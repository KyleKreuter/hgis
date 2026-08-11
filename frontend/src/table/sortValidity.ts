import { ApiError } from '@/api/client'

/**
 * Whether a failed feature-page fetch failed because the field it was told to sort by
 * no longer exists -- the one case (CONTRACT.md "Attributfelder löschen") where
 * `AttributeTable` recovers on its own by falling back to unsorted, instead of leaving
 * the table stuck on a 400 the user cannot fix from here.
 *
 * Matched by the backend's own wording (`FeatureQueryService.resolveSortField` throws
 * "Unbekanntes Sortierfeld: …") rather than by status code alone: a bad filter
 * expression is also a 400, but reports "Unbekanntes Feld: …" instead, and the filter is
 * deliberately left alone (CONTRACT.md) -- it is a free-form expression the server
 * already reports visibly, with no local state to reset.
 *
 * That makes this a match on a string owned by the other side of the wire. It is held in
 * place from there by `FeatureQueryServiceTest.rejectsAnUnknownSortField`, which carries
 * the same warning in the other direction -- neither wording moves without the other.
 */
export function isUnknownSortFieldError(error: unknown): boolean {
  return (
    error instanceof ApiError && error.status === 400 && error.message.includes('Unbekanntes Sortierfeld')
  )
}
