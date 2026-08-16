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
 *
 * Deliberately *not* matched here: "Mehrdeutiges Feld: …", the server's answer to a name
 * that means two fields at once. The one caller of this function clears the filter the
 * user typed, and doing that would take the message away with it -- see
 * `isAmbiguousFilterFieldError` below for why that message is the one thing worth keeping.
 */
export function isUnknownFilterFieldError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400 && error.message.includes('Unbekanntes Feld')
}

/**
 * Whether the filter names a field that exists twice over: the server answers
 * "Mehrdeutiges Feld: …" for a name that is one field's display name and another's column
 * name -- the Straßenbaumkataster carries two such pairs.
 *
 * Separate from the check above, and with no caller yet, on purpose. An unknown field is
 * gone and the filter has to be dropped; an ambiguous one is still there, and the server's
 * message already carries the correction -- the names that resolve, and the field ids,
 * which always resolve. `FilterBar` renders any 400 verbatim under the input
 * (`FilterBar.tsx`, `const message = … error.message`), so the user reads that correction
 * and can act on it. Clearing the expression here would delete both the filter and the
 * only sentence that says how to write it correctly.
 *
 * It is exported so `AttributeTable` can tell the two cases apart the moment it is free to
 * change -- for instance to offer the field id rather than to reset. Until then this file
 * classifies the error without acting on it, which is the difference between "the frontend
 * cannot place this 400" and "the frontend places it and chooses to leave it visible".
 */
export function isAmbiguousFilterFieldError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400 && error.message.includes('Mehrdeutiges Feld')
}
