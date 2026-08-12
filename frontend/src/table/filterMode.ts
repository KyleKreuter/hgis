/**
 * The filter bar's two input modes -- see CONTRACT.md.
 *
 * `'filter'` is the pre-existing expression syntax (AND/OR/NOT, comparisons, LIKE,
 * IN, IS NULL...). `'search'` is the new syntax-free mode: plain text, matched as a
 * partial hit against every text field. Which one is meant is never guessed from the
 * typed text -- a search term that happens to contain `=` or `AND` would guess wrong --
 * so the mode is always an explicit choice, never inferred.
 */
export type FilterMode = 'search' | 'filter'

/** Flips to the other mode. There are only two, so a toggle needs no target argument. */
export function toggleFilterMode(mode: FilterMode): FilterMode {
  return mode === 'search' ? 'filter' : 'search'
}
