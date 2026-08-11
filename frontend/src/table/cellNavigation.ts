/**
 * Keyboard navigation over the table's grid of attribute cells -- pure functions, no
 * DOM and no store, so the keyboard behaviour (CONTRACT.md) can be tested without
 * mounting the virtualised table.
 *
 * The grid is addressed by `{ row, column }`: `row` is the position in the currently
 * loaded rows (not the fid), `column` is the position in the field list (not counting
 * the fid column or the zoom button, neither of which takes part in cell editing).
 */

export interface CellPosition {
  row: number
  column: number
}

export type ArrowKey = 'ArrowUp' | 'ArrowDown' | 'ArrowLeft' | 'ArrowRight'

/** Which direction a confirmed edit (Enter or Tab) advances the focus. */
export type AdvanceDirection = 'down' | 'left' | 'right'

/** What a key press means while a cell has focus but is not being edited. */
export type FocusKeyAction =
  | { type: 'move'; direction: ArrowKey }
  | { type: 'startEdit' }
  /** A printable character was typed -- start editing and overwrite with it. */
  | { type: 'startEditWithChar'; char: string }

/** What a key press means while a cell is being edited. */
export type EditKeyAction =
  | { type: 'commit'; advance: AdvanceDirection }
  | { type: 'cancel' }

function clamp(value: number, max: number): number {
  if (max < 0) return 0
  return Math.min(Math.max(value, 0), max)
}

/** Moves focus by one arrow-key step. Clamps at the grid's edges rather than wrapping. */
export function moveFocus(
  position: CellPosition,
  direction: ArrowKey,
  rowCount: number,
  columnCount: number,
): CellPosition {
  switch (direction) {
    case 'ArrowUp':
      return { row: clamp(position.row - 1, rowCount - 1), column: position.column }
    case 'ArrowDown':
      return { row: clamp(position.row + 1, rowCount - 1), column: position.column }
    case 'ArrowLeft':
      return { row: position.row, column: clamp(position.column - 1, columnCount - 1) }
    case 'ArrowRight':
      return { row: position.row, column: clamp(position.column + 1, columnCount - 1) }
  }
}

/**
 * Where focus lands after confirming an edit: Enter goes a row down, Tab goes a column
 * over (CONTRACT.md's keyboard table). Clamped the same way `moveFocus` is -- confirming
 * the last row's edit with Enter simply stays there rather than leaving the grid.
 */
export function advanceFocus(
  position: CellPosition,
  advance: AdvanceDirection,
  rowCount: number,
  columnCount: number,
): CellPosition {
  switch (advance) {
    case 'down':
      return { row: clamp(position.row + 1, rowCount - 1), column: position.column }
    case 'left':
      return { row: position.row, column: clamp(position.column - 1, columnCount - 1) }
    case 'right':
      return { row: position.row, column: clamp(position.column + 1, columnCount - 1) }
  }
}

/** Keeps a position inside a grid that just changed size (e.g. a filter emptied rows). */
export function clampToGrid(position: CellPosition, rowCount: number, columnCount: number): CellPosition {
  return { row: clamp(position.row, rowCount - 1), column: clamp(position.column, columnCount - 1) }
}

interface KeyLike {
  key: string
  shiftKey?: boolean
  ctrlKey?: boolean
  metaKey?: boolean
  altKey?: boolean
}

/**
 * What a key press means for a focused-but-not-editing cell, or `null` when the table
 * has no business with it (the key is left to do whatever it would do otherwise).
 */
export function focusKeyAction(event: KeyLike): FocusKeyAction | null {
  switch (event.key) {
    case 'ArrowUp':
    case 'ArrowDown':
    case 'ArrowLeft':
    case 'ArrowRight':
      return { type: 'move', direction: event.key }
    case 'Enter':
      return { type: 'startEdit' }
    default:
      // A held modifier turns the key into a shortcut (copy, browser search, ...), not
      // a request to type into the cell.
      if (event.ctrlKey || event.metaKey || event.altKey) return null
      if (event.key.length === 1) return { type: 'startEditWithChar', char: event.key }
      return null
  }
}

/** What a key press means while a cell is open for editing. */
export function editKeyAction(event: KeyLike): EditKeyAction | null {
  switch (event.key) {
    case 'Escape':
      return { type: 'cancel' }
    case 'Enter':
      return { type: 'commit', advance: 'down' }
    case 'Tab':
      return { type: 'commit', advance: event.shiftKey ? 'left' : 'right' }
    default:
      return null
  }
}
