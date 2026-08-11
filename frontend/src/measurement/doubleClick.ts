/**
 * Telling the second click of a double-click apart from a deliberate second vertex.
 *
 * A double-click delivers both of its clicks as ordinary `click` events before the
 * `dblclick` arrives, and the hand moves a pixel or two in between. A pixel is tens of
 * metres at city zoom, so the pair has to be recognised in screen space -- comparing
 * coordinates would let the jitter through as a real, and badly wrong, vertex.
 *
 * The window is short on purpose. A generous one swallows deliberate clicks: two
 * vertices a third of a second apart are perfectly normal when tracing a line quickly,
 * and losing them is worse than the alternative -- a slow double-click that does place
 * its second click, which `wasSecondClickPlaced` then takes back once the double-click
 * is a fact rather than a guess.
 */

/** Window in which a second click counts as half of a double-click rather than a vertex. */
export const DOUBLE_CLICK_MS = 250

/** How far the pointer may drift between the two clicks, in screen pixels. */
export const DOUBLE_CLICK_PX = 4

export interface ClickPoint {
  x: number
  y: number
}

export interface ClickRecord extends ClickPoint {
  /** `event.timeStamp`, in milliseconds. */
  time: number
  /** Whether this click actually became a vertex. */
  placed: boolean
}

/** True when `next` is the second click of a double-click on `previous`. */
export function isSecondClick(
  previous: ClickRecord | null,
  next: ClickPoint & { time: number },
): boolean {
  if (!previous) return false
  return (
    next.time - previous.time < DOUBLE_CLICK_MS &&
    Math.hypot(next.x - previous.x, next.y - previous.y) < DOUBLE_CLICK_PX
  )
}

/**
 * True when the double-click now being handled placed a vertex it should not have --
 * the case `isSecondClick` was too strict to catch.
 */
export function wasSecondClickPlaced(last: ClickRecord | null, at: ClickPoint): boolean {
  if (!last?.placed) return false
  return Math.hypot(at.x - last.x, at.y - last.y) < DOUBLE_CLICK_PX
}
