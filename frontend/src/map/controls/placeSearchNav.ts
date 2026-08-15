/**
 * Keyboard navigation for `PlaceSearchControl`'s result list (CONTRACT.md "Ortssuche":
 * "Pfeiltasten durch die Liste, Eingabetaste wählt, Escape schließt").
 *
 * `-1` means the input itself is highlighted, not any row -- the state Enter must do
 * nothing in, and the state both arrow keys cycle back through. Without it, arriving at
 * the last row with no way back except retyping would make the list feel like a trap.
 */
export function moveHighlight(current: number, count: number, direction: 'down' | 'up'): number {
  if (count === 0) return -1
  if (direction === 'down') {
    return current + 1 >= count ? -1 : current + 1
  }
  return current - 1 < -1 ? count - 1 : current - 1
}
