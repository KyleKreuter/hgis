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
  /**
   * Set once `updateGeometry` has touched this feature. `geometry` itself is always
   * present -- a property-only edit carries the untouched geometry forward unchanged --
   * so its mere presence cannot say whether the shape actually moved. This flag is the
   * one place that tells the two apart, and `dirtyFids` relies on it.
   */
  geometryChanged?: boolean
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
  /**
   * Counts buffer changes that did not start in the drawing tool: undo, redo, and
   * deletes triggered from the toolbar.
   *
   * The drawing tool holds its own copy of every geometry and cannot see a patch being
   * applied here. Every other change to the buffer starts in the tool itself, so this is
   * the one signal that says "the buffer moved without you". Monotonic on purpose --
   * resetting it on `begin` would make a later step collide with an earlier value.
   */
  historyNonce: number

  /**
   * Whether a shape is half-drawn: corners set, not yet closed.
   *
   * Deliberately not part of {@link countChanges}. It is not a change -- nothing has
   * reached the buffer yet, and the toolbar counter would be lying if it said "1
   * ungespeicherte Änderung" for three clicks that are not a polygon yet. But it *is*
   * work, and anything that ends the drawing session throws it away, so every guard that
   * asks "would leaving now cost the user something" has to count it (`hasUnsavedWork`).
   *
   * Only {@code DrawController} writes this: terra-draw is the one place that knows
   * whether its current mode is mid-shape.
   */
  sketching: boolean

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
  /** Reported by {@code DrawController} whenever terra-draw's own state changes. */
  setSketching: (sketching: boolean) => void
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
    historyNonce: 0,
    sketching: false,

    // `sketching` is cleared by both: a session that starts or ends has no half-drawn
    // shape, and leaving it set would keep every guard blocked for a tool that is gone.
    begin: (layerId) =>
      set({ layerId, buffer: EMPTY_BUFFER, undoStack: [], redoStack: [], nextTempId: -1,
        sketching: false }),

    end: () =>
      set({ layerId: null, buffer: EMPTY_BUFFER, undoStack: [], redoStack: [], nextTempId: -1,
        sketching: false }),

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
          // Set last and unconditionally: this is the only place a shape is recorded as
          // moved, and no earlier spread in this assignment gets to overrule it.
          target[feature.fid] = {
            ...feature,
            ...target[feature.fid],
            geometry,
            geometryChanged: true,
          }
        },
        'Geometrie geändert',
        `geometry:${feature.fid}`,
      ),

    updateProperties: (feature, properties) =>
      mutate(
        (draft) => {
          const target = feature.fid < 0 ? draft.creates : draft.updates
          const existing = target[feature.fid] ?? feature
          // Deliberately not touching `geometryChanged`: it carries forward from
          // `existing`, so a property-only edit leaves it unset and a later one on a
          // feature whose geometry already moved keeps it set.
          target[feature.fid] = { ...existing, properties }
        },
        'Attribute geändert',
        `properties:${feature.fid}`,
      ),

    removeFeature: (fid, base) => {
      const previous = get().buffer
      mutate((draft) => {
        if (fid < 0) {
          // Never existed on the server, so deleting it is simply forgetting it.
          delete draft.creates[fid]
          return
        }
        delete draft.updates[fid]
        if (!draft.deletes.includes(fid)) draft.deletes.push(fid)
        void base
      }, 'Objekt gelöscht')
      // No-op deletes must not bump the nonce -- otherwise the sync would run for nothing.
      if (get().buffer === previous) return
      // Delete-key removals already emptied the surface; a toolbar delete has not.
      // Bumping the nonce lets DrawController drop the shape in either case -- when the
      // feature is already gone the sync is a no-op.
      set({ historyNonce: get().historyNonce + 1 })
    },

    undo: () => {
      const { buffer, undoStack, redoStack, historyNonce } = get()
      const entry = undoStack.at(-1)
      if (!entry) return
      set({
        buffer: applyPatches(buffer, entry.inversePatches),
        undoStack: undoStack.slice(0, -1),
        redoStack: [...redoStack, entry],
        historyNonce: historyNonce + 1,
      })
    },

    redo: () => {
      const { buffer, undoStack, redoStack, historyNonce } = get()
      const entry = redoStack.at(-1)
      if (!entry) return
      set({
        buffer: applyPatches(buffer, entry.patches),
        undoStack: [...undoStack, entry],
        redoStack: redoStack.slice(0, -1),
        historyNonce: historyNonce + 1,
      })
    },

    reset: () => set({ buffer: EMPTY_BUFFER, undoStack: [], redoStack: [] }),

    setSketching: (sketching) => set((state) => (state.sketching === sketching ? state : { sketching })),
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
 * fids of existing features whose tile no longer matches the buffer.
 *
 * The tile layers hide exactly these: a feature whose geometry changed still has its old
 * outline baked into the tile, and without hiding it the edited version would be drawn on
 * top of its own outdated self. A feature edited through `updateProperties` alone has no
 * such conflict -- its tile geometry is still correct, only an attribute differs, and that
 * attribute does not render on the map at all. Hiding it anyway would just punch a
 * groundless hole where nobody is drawing a replacement.
 */
export function dirtyFids(buffer: Buffer): number[] {
  const geometryChanges = Object.values(buffer.updates)
    .filter((feature) => feature.geometryChanged)
    .map((feature) => feature.fid)
  return [...geometryChanges, ...buffer.deletes]
}
