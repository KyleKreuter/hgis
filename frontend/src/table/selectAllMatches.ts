/**
 * Threshold for "select all matches" -- see CONTRACT.md.
 *
 * Mirrors `rectangleSelectPaging.ts`'s "ask before this many" rule: same number, same
 * reasoning. Loading is cheap for the backend either way, but silently replacing the
 * selection with tens of thousands of objects behind one click is surprising, while
 * asking for a handful of filter results would just be friction. The server-side
 * ceiling (100,000, CONTRACT.md) is a separate, harder limit this module does not
 * need to know about -- that one comes back as a 400 to react to, not to predict.
 */
export const SELECT_ALL_MATCHES_CONFIRM_THRESHOLD = 1000

/** Whether the match count needs an explicit "select all N?" confirmation first. */
export function needsSelectAllConfirmation(totalCount: number): boolean {
  return totalCount > SELECT_ALL_MATCHES_CONFIRM_THRESHOLD
}
