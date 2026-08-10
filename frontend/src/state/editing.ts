import { applyPatches, enablePatches, produceWithPatches, type Patch } from 'immer'
import { create } from 'zustand'

enablePatches()

export interface DraftFeature {
  /** Negative for features that do not exist on the server yet. */
  fid: number
  geometry: GeoJSON.Geometry
  properties: Record<string, unknown>
  /** xmin of the row this draft is based on; absent for new features. */
  rowVersion?: string
}

interface Buffer {
  creates: Record<number, DraftFeature>
  updates: Record<number, DraftFeature>
  deletes: number[]
}

interface HistoryEntry {
  patches: Patch[]
  inversePatches: Patch[]
  label: string
  /** Identifies a run of changes that should collapse into one undo step. */
  coalesceKey?: string
  at: number
}

interface EditingState {
  layerId: string | null
  buffer: Buffer
  undoStack: HistoryEntry[]
  redoStack: HistoryEntry[]
  /**
   * Next temporary id, counting down. Kept outside the buffer so undo never rolls it
   * back: deriving it from the buffer would reissue the id of an undone creation, and
   * the drawing tool would then hold two different features under one id.
   */
  nextTempId: number

  begin: (layerId: string) => void
  end: () => void

  /** Hands out the next temporary id and advances the counter. */
  takeTempId: () => number
  addFeature: (
    geometry: GeoJSON.Geometry,
    properties?: Record<string, unknown>,
    /** Reuses an id already handed out by {@link takeTempId}; omit to take a fresh one. */
    fid?: number,
  ) => number
  updateGeometry: (feature: DraftFeature, geometry: GeoJSON.Geometry) => void
  updateProperties: (feature: DraftFeature, properties: Record<string, unknown>) => void
  removeFeature: (fid: number, base?: DraftFeature) => void

  undo: () => void
  redo: () => void
  /** Called after a successful save: undo must never reach back past persisted state. */
  reset: () => void
}

const EMPTY_BUFFER: Buffer = { creates: {}, updates: {}, deletes: [] }

/**
 * Changes made since the last save.
 *
 * <p>Temporary fids are negative. Real ones are always positive, so the sign alone says
 * whether a feature exists on the server -- no extra flag to keep in sync, and the map
 * can colour new features by it.
 *
 * <p>Undo/redo runs on Immer patches rather than hand-written apply/revert pairs:
 * `produceWithPatches` hands back the inverse of every change for free, and an inverse
 * derived from the change itself cannot drift away from it.
 */
export const useEditing = create<EditingState>((set, get) => {
  /** Window in which repeated changes to the same thing collapse into one undo step. */
  const COALESCE_MS = 600

  function mutate(
    recipe: (draft: Buffer) => void,
    label: string,
    coalesceKey?: string,
  ) {
    const state = get()
    const [next, patches, inversePatches] = produceWithPatches(state.buffer, recipe)
    if (patches.length === 0) return

    const previous = state.undoStack.at(-1)
    const now = Date.now()

    // Dragging a vertex produces a change per mouse move. Without collapsing them, taking
    // one drag back would cost the user two hundred presses of undo.
    const collapse =
      coalesceKey !== undefined &&
      previous?.coalesceKey === coalesceKey &&
      now - previous.at < COALESCE_MS

    const entry: HistoryEntry = collapse
      ? {
          patches: [...previous.patches, ...patches],
          // The inverse has to stay in reverse order: undoing means walking the changes
          // backwards, so the newest inverse comes first.
          inversePatches: [...inversePatches, ...previous.inversePatches],
          label: previous.label,
          coalesceKey,
          at: now,
        }
      : { patches, inversePatches, label, coalesceKey, at: now }

    set({
      buffer: next,
      undoStack: collapse
        ? [...state.undoStack.slice(0, -1), entry]
        : [...state.undoStack, entry],
      // Any new change abandons the redone future -- it belongs to a history that no
      // longer happened.
      redoStack: [],
    })
  }

  return {
    layerId: null,
    buffer: EMPTY_BUFFER,
    undoStack: [],
    redoStack: [],
    nextTempId: -1,

    begin: (layerId) =>
      set({ layerId, buffer: EMPTY_BUFFER, undoStack: [], redoStack: [], nextTempId: -1 }),

    end: () =>
      set({ layerId: null, buffer: EMPTY_BUFFER, undoStack: [], redoStack: [], nextTempId: -1 }),

    takeTempId: () => {
      const fid = get().nextTempId
      set({ nextTempId: fid - 1 })
      return fid
    },

    addFeature: (geometry, properties = {}, fid) => {
      const id = fid ?? get().takeTempId()

      mutate((draft) => {
        draft.creates[id] = { fid: id, geometry, properties }
      }, 'Objekt gezeichnet')

      return id
    },

    updateGeometry: (feature, geometry) =>
      mutate(
        (draft) => {
          const target = feature.fid < 0 ? draft.creates : draft.updates
          target[feature.fid] = { ...feature, ...target[feature.fid], geometry }
        },
        'Geometrie geändert',
        `geometry:${feature.fid}`,
      ),

    updateProperties: (feature, properties) =>
      mutate(
        (draft) => {
          const target = feature.fid < 0 ? draft.creates : draft.updates
          const existing = target[feature.fid] ?? feature
          target[feature.fid] = { ...existing, properties }
        },
        'Attribute geändert',
        `properties:${feature.fid}`,
      ),

    removeFeature: (fid, base) =>
      mutate((draft) => {
        if (fid < 0) {
          // Never existed on the server, so deleting it is simply forgetting it.
          delete draft.creates[fid]
          return
        }
        delete draft.updates[fid]
        if (!draft.deletes.includes(fid)) draft.deletes.push(fid)
        void base
      }, 'Objekt gelöscht'),

    undo: () => {
      const { buffer, undoStack, redoStack } = get()
      const entry = undoStack.at(-1)
      if (!entry) return
      set({
        buffer: applyPatches(buffer, entry.inversePatches),
        undoStack: undoStack.slice(0, -1),
        redoStack: [...redoStack, entry],
      })
    },

    redo: () => {
      const { buffer, undoStack, redoStack } = get()
      const entry = redoStack.at(-1)
      if (!entry) return
      set({
        buffer: applyPatches(buffer, entry.patches),
        undoStack: [...undoStack, entry],
        redoStack: redoStack.slice(0, -1),
      })
    },

    reset: () => set({ buffer: EMPTY_BUFFER, undoStack: [], redoStack: [] }),
  }
})

/** How many unsaved changes the buffer holds. Drives the toolbar counter. */
export function countChanges(buffer: Buffer): number {
  return (
    Object.keys(buffer.creates).length +
    Object.keys(buffer.updates).length +
    buffer.deletes.length
  )
}

/**
 * fids of existing features the buffer has touched.
 *
 * The tile layers hide exactly these: their tiles still show the old geometry, and
 * without hiding them the edited version would be drawn on top of its own outdated self.
 */
export function dirtyFids(buffer: Buffer): number[] {
  return [...Object.keys(buffer.updates).map(Number), ...buffer.deletes]
}
