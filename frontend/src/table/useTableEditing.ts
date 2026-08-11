import { create } from 'zustand'
import type { CellPosition } from './cellNavigation'
import * as session from './tableEditSession'
import type { TableEditState } from './tableEditSession'

interface TableEditStore extends TableEditState {
  /** Keyboard focus in the field-column grid; null before the user has touched it. */
  focus: CellPosition | null
  /**
   * The cell currently open for typing, or null. Deliberately not derived from `focus`:
   * moving focus must not open an editor by itself (CONTRACT.md -- only Enter or typing
   * does), and the two can point at different cells for one render while a commit is in
   * flight.
   */
  editingCell: CellPosition | null
  /**
   * The value shown while `editingCell` is open, not yet written to `edits`. Escape
   * drops it untouched; a commit writes it through `setCell`. Held here rather than in
   * the cell component's own state because the table is virtualised -- scrolling a row
   * out of view unmounts it, and a half-typed value would be lost with it.
   */
  draft: unknown

  begin: (layerId: string) => void
  end: () => void
  reset: () => void
  setCell: (
    fid: number,
    columnName: string,
    value: unknown,
    rowVersion: string | undefined,
    originalValue: unknown,
  ) => void
  revertCell: (fid: number, columnName: string) => void
  changeCount: () => number

  setFocus: (position: CellPosition | null) => void
  /** Opens `position` for editing, seeded with `initialValue` (the cell's current value). */
  startEditing: (position: CellPosition, initialValue: unknown) => void
  setDraft: (value: unknown) => void
  /** Escape: closes the editor without touching `edits`. */
  cancelEditing: () => void
  /** Enter/Tab: writes `draft` through `setCell`, then moves focus to `nextFocus`. */
  commitEditing: (
    fid: number,
    columnName: string,
    rowVersion: string | undefined,
    originalValue: unknown,
    nextFocus: CellPosition,
  ) => void
}

/**
 * The table's edit buffer, its own store separate from `useEditing` (the map's draw
 * session) -- see `tableEditSession.ts` for why.
 */
export const useTableEditing = create<TableEditStore>((set, get) => ({
  ...session.IDLE,
  focus: null,
  editingCell: null,
  draft: null,

  begin: (layerId) => set({ ...session.begin(layerId), focus: null, editingCell: null, draft: null }),
  end: () => set({ ...session.end(), focus: null, editingCell: null, draft: null }),
  reset: () => set((state) => ({ ...session.reset(state), editingCell: null, draft: null })),

  setCell: (fid, columnName, value, rowVersion, originalValue) =>
    set((state) => session.setCell(state, fid, columnName, value, rowVersion, originalValue)),
  revertCell: (fid, columnName) => set((state) => session.revertCell(state, fid, columnName)),
  changeCount: () => session.changeCount(get()),

  setFocus: (position) => set({ focus: position }),
  startEditing: (position, initialValue) => set({ editingCell: position, focus: position, draft: initialValue }),
  setDraft: (value) => set({ draft: value }),
  cancelEditing: () => set({ editingCell: null, draft: null }),

  commitEditing: (fid, columnName, rowVersion, originalValue, nextFocus) =>
    set((state) => ({
      ...session.setCell(state, fid, columnName, state.draft, rowVersion, originalValue),
      editingCell: null,
      draft: null,
      focus: nextFocus,
    })),
}))

/** True while the table's edit mode is on. Drives the map/table mode exclusion. */
export function useIsTableEditing(): boolean {
  return useTableEditing((state) => state.active)
}
