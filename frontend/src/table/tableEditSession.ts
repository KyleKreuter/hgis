/**
 * The table's own edit buffer -- pure state transitions, no zustand and no React.
 *
 * Deliberately separate from `state/editing.ts` (plan section D.4 / CONTRACT.md): that
 * buffer belongs to the map's draw session (geometry creates, updates, deletes, undo
 * history) and the table never touches it. Two independent buffers on the same features
 * would be unmanageable, so the workspace keeps only one of the two modes active at a
 * time -- see `routes/projects.$projectId.tsx`.
 *
 * `useTableEditing.ts` wraps this in a zustand store and adds the UI-only pieces
 * (keyboard focus, the cell currently being typed into) that have to live in the store
 * too, because the table is virtualised and a row's own state does not survive being
 * scrolled out of view.
 */

export interface TableEditState {
  layerId: string | null
  /** fid -> changed columns. A column present with value `null` means "set to NULL". */
  edits: Map<number, Record<string, unknown>>
  /** fid -> rowVersion captured when the fid's first edit was made. */
  rowVersions: Map<number, string>
  active: boolean
}

export const IDLE: TableEditState = {
  layerId: null,
  edits: new Map(),
  rowVersions: new Map(),
  active: false,
}

export function begin(layerId: string): TableEditState {
  return { layerId, edits: new Map(), rowVersions: new Map(), active: true }
}

export function end(): TableEditState {
  return IDLE
}

/** Clears the buffer but keeps the mode active -- same shape as `useEditing.reset`. */
export function reset(state: TableEditState): TableEditState {
  return { ...state, edits: new Map(), rowVersions: new Map() }
}

/**
 * Sets one cell.
 *
 * `value === null` is a real value (NULL) and is stored just like any other. A value
 * that matches what the row already had is not a pending change, though -- typing
 * something back to its original is not editing, and the row must not stay marked dirty
 * for it (CONTRACT.md: "ein auf den Ausgangswert zurückgesetzter Wert zählt nicht als
 * Änderung").
 */
export function setCell(
  state: TableEditState,
  fid: number,
  columnName: string,
  value: unknown,
  rowVersion: string | undefined,
  originalValue: unknown,
): TableEditState {
  const row = { ...(state.edits.get(fid) ?? {}) }

  if (value === originalValue) {
    delete row[columnName]
  } else {
    row[columnName] = value
  }

  return commitRow(state, fid, row, rowVersion)
}

/** Drops one column's pending change, restoring the row's server value for it. */
export function revertCell(state: TableEditState, fid: number, columnName: string): TableEditState {
  const existing = state.edits.get(fid)
  if (!existing || !(columnName in existing)) return state

  const row = { ...existing }
  delete row[columnName]
  return commitRow(state, fid, row, undefined)
}

/** Writes `row` back for `fid`, dropping the fid entirely once it has no changes left. */
function commitRow(
  state: TableEditState,
  fid: number,
  row: Record<string, unknown>,
  rowVersion: string | undefined,
): TableEditState {
  const edits = new Map(state.edits)
  const rowVersions = new Map(state.rowVersions)

  if (Object.keys(row).length === 0) {
    edits.delete(fid)
    rowVersions.delete(fid)
  } else {
    edits.set(fid, row)
    // Every edit to the same row carries the same rowVersion (it comes from the one
    // query-cache entry for that fid), so which write wins here never actually differs.
    if (rowVersion !== undefined) rowVersions.set(fid, rowVersion)
  }

  return { ...state, edits, rowVersions }
}

/** True while `columnName` of `fid` holds a pending change. Drives the dirty highlight. */
export function hasEdit(state: TableEditState, fid: number, columnName: string): boolean {
  const row = state.edits.get(fid)
  return row !== undefined && columnName in row
}

/** The value to show: the pending edit if there is one, otherwise `fallback`. */
export function cellValue(state: TableEditState, fid: number, columnName: string, fallback: unknown): unknown {
  const row = state.edits.get(fid)
  return row && columnName in row ? row[columnName] : fallback
}

/** How many cells are pending -- across every row, not just the focused one. */
export function changeCount(state: TableEditState): number {
  let count = 0
  for (const row of state.edits.values()) count += Object.keys(row).length
  return count
}
