/**
 * Reordering logic of the layer tree, kept apart from the component because it is where
 * the two easy mistakes live: the off-by-one when an item is removed before it is
 * reinserted, and the direction flip between what the tree shows and what the API takes.
 */

/**
 * Moves the entry at `from` to the insertion point `before`.
 *
 * `before` is a gap between elements, not an index: 0 is above everything,
 * `items.length` below everything. Splicing the item out first shifts every later
 * element up by one, so any insertion point beyond the source has to be corrected --
 * without that, moving an item downwards always lands one slot short.
 */
export function moveItem<T>(items: T[], from: number, before: number): T[] {
  const next = [...items]
  const [moved] = next.splice(from, 1)
  next.splice(before > from ? before - 1 : before, 0, moved)
  return next
}

/**
 * The order to send to `PUT /api/projects/{id}/layers/order` after a move in the tree.
 *
 * The tree lists layers top-first, the endpoint expects them bottom-first (matching
 * `zIndex`, which counts upwards from the bottom), hence the reversal.
 */
export function reorderedIdsBottomToTop<T extends { id: string }>(
  displayedTopToBottom: T[],
  from: number,
  before: number,
): string[] {
  return moveItem(displayedTopToBottom, from, before)
    .map((item) => item.id)
    .reverse()
}

/** True when a drop would not change anything -- dropping a row onto either of its own edges. */
export function isNoOpMove(from: number, before: number): boolean {
  return from < 0 || before === from || before === from + 1
}
