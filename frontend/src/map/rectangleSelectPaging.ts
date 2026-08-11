/**
 * Threshold and pagination rules for loading a rectangle selection -- see CONTRACT.md.
 *
 * Split out from `RectangleSelectTool` so the "ask before this many, refuse beyond that
 * many" policy, and the loop that pages through the backend's cursor, are facts that
 * can be checked without a network or a map.
 */

/** Above this count, the tool asks before loading -- see `needsConfirmation`. */
export const RECTANGLE_SELECT_CONFIRM_THRESHOLD = 1000

/** Same ceiling as the export; beyond this the tool refuses outright. */
export const RECTANGLE_SELECT_MAX = 100_000

/** Whether the count found needs an explicit "select all N?" confirmation before loading. */
export function needsConfirmation(totalCount: number): boolean {
  return totalCount > RECTANGLE_SELECT_CONFIRM_THRESHOLD
}

/** Whether the count is beyond what the tool is willing to load at all. */
export function exceedsMaximum(totalCount: number): boolean {
  return totalCount > RECTANGLE_SELECT_MAX
}

/** One page of fids, as far as the pagination loop below cares. */
export interface FidPage {
  features: readonly { fid: number }[]
  nextCursor?: string | null
}

export interface CollectResult {
  fids: number[]
  /**
   * Set once more than `max` fids have arrived. The caller must not act on `fids` in
   * that case -- see `collectAllFids`.
   */
  truncated: boolean
}

/**
 * Pages through `fetchPage` -- opaque cursor in, next page out -- until `nextCursor`
 * is empty, collecting fids along the way.
 *
 * Stops early once more fids have arrived than `max` allows, rather than trusting the
 * count from an earlier request: the count check and this load are two separate round
 * trips, and the data can change in between. `truncated: true` is the signal that the
 * result is incomplete and must not be treated as the whole answer.
 */
export async function collectAllFids(
  fetchPage: (cursor?: string) => Promise<FidPage>,
  max: number = RECTANGLE_SELECT_MAX,
): Promise<CollectResult> {
  const fids: number[] = []
  let cursor: string | undefined

  for (;;) {
    const page = await fetchPage(cursor)
    for (const feature of page.features) fids.push(feature.fid)

    if (fids.length > max) return { fids, truncated: true }
    if (!page.nextCursor) return { fids, truncated: false }
    cursor = page.nextCursor
  }
}
