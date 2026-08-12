import { ApiError } from '@/api/client'

/**
 * Whether a failed feature-page fetch failed because the filter or search expression
 * names a field that no longer exists -- the filter's counterpart to `sortValidity.ts`'s
 * `isUnknownSortFieldError` (CONTRACT.md phase 17, "Filter oder Sortierung zeigen auf ein
 * gelöschtes Feld"). A restored filter can point at a field deleted after it was saved;
 * a freshly typed one can just as easily be a plain typo, and the server cannot tell the
 * two apart -- so this recovers from either the same way `AttributeTable` already
 * recovers from a stale sort field.
 *
 * Matched by wording, the same way `isUnknownSortFieldError` is: `FilterParser.resolveField`
 * throws "Unbekanntes Feld: …", a different message from the sort field's own "Unbekanntes
 * Sortierfeld: …". The two checks are deliberately disjoint, each held in place by the
 * other's negative test case -- neither wording may change without the other noticing.
 */
export function isUnknownFilterFieldError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400 && error.message.includes('Unbekanntes Feld')
}
