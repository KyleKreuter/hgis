import { create } from 'zustand'

/**
 * How an incoming set of fids is folded into the current selection -- see `select`.
 * `'replace'` is the whole of the original contract; `'add'`/`'subtract'` are what the
 * rectangle select tool needs for Shift/Alt-modified drags.
 */
export type SelectionMode = 'replace' | 'add' | 'subtract'

interface SelectionState {
  /** The layer the selection belongs to. Selecting in another layer replaces it. */
  layerId: string | null
  selected: Set<number>

  toggle: (layerId: string, fid: number) => void
  /** `mode` defaults to `'replace'`, which is the previous, still-supported behaviour. */
  select: (layerId: string, fids: number[], mode?: SelectionMode) => void
  clear: () => void
}

/**
 * Folds `fids` into `base` according to `mode`. `'replace'` ignores `base` entirely --
 * same as the original `select` -- so a mode-less call is byte-for-byte what it always
 * was.
 */
function applySelectionMode(
  base: ReadonlySet<number>,
  fids: readonly number[],
  mode: SelectionMode,
): Set<number> {
  if (mode === 'replace') return new Set(fids)

  const next = new Set(base)
  if (mode === 'add') {
    for (const fid of fids) next.add(fid)
  } else {
    for (const fid of fids) next.delete(fid)
  }
  return next
}

/**
 * Which features are selected, as one fact both the map and the attribute table read
 * and write.
 *
 * Deliberately a single store rather than state on either side: with two copies, one of
 * them has to tell the other about every change, and that loop is what makes selection
 * flicker or fight itself. Here a click on the map and a click on a row are the same
 * write, and both views re-render from it.
 *
 * Pure UI state, so it stays out of TanStack Query -- nothing here is worth persisting,
 * and it is gone the moment the layer changes.
 */
export const useSelection = create<SelectionState>((set) => ({
  layerId: null,
  selected: new Set(),

  toggle: (layerId, fid) =>
    set((state) => {
      // A fid only identifies a row within its layer, so a selection from another layer
      // must not survive -- fid 42 elsewhere is a different object entirely.
      const next = state.layerId === layerId ? new Set(state.selected) : new Set<number>()
      if (next.has(fid)) {
        next.delete(fid)
      } else {
        next.add(fid)
      }
      return { layerId, selected: next }
    }),

  select: (layerId, fids, mode = 'replace') =>
    set((state) => ({
      layerId,
      // 'add'/'subtract' only make sense against this layer's own selection -- a
      // selection left over from another layer is dropped first, same as `toggle`.
      selected: applySelectionMode(state.layerId === layerId ? state.selected : new Set(), fids, mode),
    })),

  clear: () => set({ layerId: null, selected: new Set() }),
}))

/** Raised for exactly the write it belongs to; see `applyRemoteSelection`. */
let applyingRemoteSelection = false

/**
 * Writes a selection that did not come from this user.
 *
 * There are two such sources, and they need the same thing: the state saved from a
 * previous session, restored when a layer is first opened, and a state another client
 * just set, which arrives over the live channel (`api/events.ts`). Neither is the user
 * doing something, so neither may be saved back out -- the first would rewrite what was
 * just read, and the second would answer someone else's change with a change of its own,
 * which is a conversation with no end.
 *
 * Raised for the write itself and lowered again the moment it returns: subscribers run
 * synchronously inside zustand's `set`, so the flag never outlives the write it belongs
 * to. A flag held any longer would swallow selections the user makes in the meantime, and
 * those are worth saving.
 */
export function applyRemoteSelection(write: () => void) {
  applyingRemoteSelection = true
  try {
    write()
  } finally {
    applyingRemoteSelection = false
  }
}

/** Whether the selection change being observed right now came from `applyRemoteSelection`. */
export function isRemoteSelection(): boolean {
  return applyingRemoteSelection
}
