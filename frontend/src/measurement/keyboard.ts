/**
 * Which key presses the measuring tool may act on, and where.
 *
 * The tool listens on `window`, because the map canvas is not reliably the focused
 * element while measuring -- and a listener that broad will hear every key press in
 * the application. Enter on a focused button, Escape in an open dialog, Backspace in
 * a text field: all of them used to end up here, and Enter/Backspace were even
 * `preventDefault`ed, so the button never fired and the dialog never closed.
 *
 * Hence an allowlist rather than a denylist of "typing targets": the keys are the
 * map's only where the event actually comes from the map. Everything is expressed as
 * pure functions over a structural target so it can be tested without a DOM.
 */

/** What the sketch should do about a key press. */
export type MeasurementKeyAction =
  /** Drop the current sketch but stay in the mode. */
  | 'clear'
  /** Leave the measuring mode altogether. */
  | 'exit'
  /** Close the sketch, as a double-click would. */
  | 'finish'
  /** Take back the last vertex. */
  | 'undo'

/**
 * The class MapLibre puts on the map container. Everything the map itself renders --
 * the canvas above all -- is inside it; our own overlay controls are siblings of it
 * and therefore deliberately outside.
 */
const MAP_CONTAINER_SELECTOR = '.maplibregl-map'

/**
 * Elements that answer to Enter, Escape or Backspace themselves. Refused wherever
 * they are, the map container included -- a control that ends up inside the map is
 * still a control.
 */
const INTERACTIVE_TAGS = new Set([
  'A',
  'BUTTON',
  'INPUT',
  'OPTION',
  'SELECT',
  'SUMMARY',
  'TEXTAREA',
])

/**
 * The part of an event target this module reads. Structural on purpose: a real
 * `HTMLElement` satisfies it, and a test can hand in an object literal.
 */
export interface KeyTargetLike {
  readonly tagName?: string
  readonly isContentEditable?: boolean
  closest?: (selector: string) => unknown
}

/**
 * True when a key press belongs to the map rather than to a control.
 *
 * Accepted: no target at all, the document/body (nothing focused, which is the normal
 * case while panning), and anything inside the map container. Refused: everything
 * else -- buttons, links, form fields, dialogs, menus and popovers, wherever they are
 * rendered, including the portals that put a menu outside the map's DOM subtree.
 */
export function isMapKeyboardContext(target: unknown): boolean {
  if (target === null || target === undefined) return true

  const element = target as KeyTargetLike
  // `window` and `document` carry no tagName; a key press that reached them was not
  // aimed at any control.
  if (typeof element.tagName !== 'string') return true

  const tag = element.tagName.toUpperCase()
  if (tag === 'BODY' || tag === 'HTML') return true
  if (element.isContentEditable || INTERACTIVE_TAGS.has(tag)) return false
  if (typeof element.closest !== 'function') return false

  return element.closest(MAP_CONTAINER_SELECTOR) != null
}

/**
 * The action a key stands for while measuring, or null when the tool has no business
 * with it.
 *
 * Escape does whichever of the two things is still undone -- clear the sketch, or
 * leave the mode -- so one press never does both at once.
 */
export function measurementKeyAction(key: string, hasSketch: boolean): MeasurementKeyAction | null {
  switch (key) {
    case 'Escape':
      return hasSketch ? 'clear' : 'exit'
    case 'Enter':
      return 'finish'
    case 'Backspace':
      return 'undo'
    default:
      return null
  }
}

/** The keys above are ours only inside the map, and only if nobody handled them first. */
export function measurementKeyEventAction(
  event: { key: string; target?: unknown; defaultPrevented?: boolean },
  hasSketch: boolean,
): MeasurementKeyAction | null {
  if (event.defaultPrevented) return null
  if (!isMapKeyboardContext(event.target)) return null
  return measurementKeyAction(event.key, hasSketch)
}
