import { create } from 'zustand'

interface SelectionState {
  /** The layer the selection belongs to. Selecting in another layer replaces it. */
  layerId: string | null
  selected: Set<number>

  toggle: (layerId: string, fid: number) => void
  select: (layerId: string, fids: number[]) => void
  clear: () => void
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

  select: (layerId, fids) => set({ layerId, selected: new Set(fids) }),

  clear: () => set({ layerId: null, selected: new Set() }),
}))
