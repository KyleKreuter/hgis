import { ApiError } from '@/api/client'

/**
 * Whether the field a feature-page fetch was told to sort by cannot be used as a sort
 * field. Two ways in, and `AttributeTable` recovers from both the same way -- falling back
 * to unsorted, instead of leaving the table stuck on a 400 the user cannot fix from here.
 *
 * 1. The field no longer exists (CONTRACT.md "Attributfelder löschen"): the server reports
 *    "Unbekanntes Sortierfeld: …".
 * 2. The name means two fields at once: the server reports "Mehrdeutiges Sortierfeld: …".
 *    A layer can carry a field whose display name is another field's column name -- the
 *    Straßenbaumkataster does it twice -- and the server refuses such a name rather than
 *    guessing, because filtering and sorting used to guess differently.
 *
 * The second case is a **recovery, not a fix**. `AttributeTable` sends a column name, and
 * for those two columns no column name resolves, so the click on the header ends up doing
 * nothing visible. That is worse than sorting and better than a table stuck on an error,
 * and it is as far as this file reaches: the real fix is for the table to sort by
 * `field.id`, which the server now accepts and which always resolves. Use
 * `isAmbiguousSortFieldError` below to tell the two apart once that lands.
 *
 * Matched by the backend's own wording rather than by status code alone: a bad filter
 * expression is also a 400, but reports "Unbekanntes Feld: …" or "Mehrdeutiges Feld: …"
 * instead, and the filter is deliberately left alone (CONTRACT.md) -- it is a free-form
 * expression the server already reports visibly, with no local state to reset. None of the
 * four wordings is a substring of another, which is what keeps the two files disjoint.
 *
 * That makes this a match on strings owned by the other side of the wire. They are held in
 * place from there by `FeatureQueryServiceTest.rejectsAnUnknownSortField` and
 * `refusesAnAmbiguousNameOnBothPaths`, which carry the same warning in the other direction
 * -- neither wording moves without the other.
 */
export function isUnknownSortFieldError(error: unknown): boolean {
  return isSortFieldError(error, 'Unbekanntes Sortierfeld') || isAmbiguousSortFieldError(error)
}

/**
 * The narrower half of the check above: the sort field exists twice over, rather than not
 * at all. Separate because the two deserve different answers once the table can send a
 * field id -- an unknown field is gone and the sort has to be dropped, an ambiguous one is
 * still there and only needs to be named precisely.
 */
export function isAmbiguousSortFieldError(error: unknown): boolean {
  return isSortFieldError(error, 'Mehrdeutiges Sortierfeld')
}

function isSortFieldError(error: unknown, wording: string): boolean {
  return error instanceof ApiError && error.status === 400 && error.message.includes(wording)
}
