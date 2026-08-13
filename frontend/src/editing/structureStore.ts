import { create } from 'zustand'
import type { SplitLinePoint } from './splitLine'

/**
 * Where the split/merge interaction currently stands.
 *
 * A store rather than component state because the three pieces sit in three different
 * slots of the workspace: the two buttons live in the header toolbar, the line is drawn
 * inside the map, and the dialogs are portalled out of both. Threading one phase through
 * the route would make it own state it has no interest in -- the same reasoning
 * `measurement/store` and `rectangleSelectStore` give.
 *
 * The fid and its `rowVersion` are captured when the tool is armed, not when the request
 * goes out: the version says which state of the row the cut was planned against, and
 * re-reading it after the line was drawn would paper over exactly the conflict the 409
 * exists to report.
 */
export type StructurePhase =
  | { type: 'idle' }
  /** The line is being drawn over feature `fid`. */
  | { type: 'drawing'; fid: number; rowVersion: string; points: SplitLinePoint[] }
  /** The line is finished and awaits the confirmation that this cannot be undone. */
  | { type: 'confirmSplit'; fid: number; rowVersion: string; line: GeoJSON.LineString }
  /** The merge dialog is open: it loads the selected features and asks which one leads. */
  | { type: 'merge' }

interface StructureState {
  phase: StructurePhase

  startSplit: (fid: number, rowVersion: string) => void
  /** Records a click while drawing. Ignored in every other phase. */
  addPoint: (point: SplitLinePoint) => void
  /** Takes back the last click. */
  undoPoint: () => void
  /** Drops the sketch but stays armed -- Escape's first meaning. */
  clearPoints: () => void
  /** The drawn line is done; ask before writing. */
  finishLine: (line: GeoJSON.LineString) => void
  /** Back from the confirmation to the drawing, with the sketch dropped. */
  redrawLine: () => void
  openMerge: () => void
  cancel: () => void
}

const IDLE: StructurePhase = { type: 'idle' }

export const useStructure = create<StructureState>((set, get) => ({
  phase: IDLE,

  startSplit: (fid, rowVersion) => set({ phase: { type: 'drawing', fid, rowVersion, points: [] } }),

  addPoint: (point) => {
    const { phase } = get()
    if (phase.type !== 'drawing') return
    set({ phase: { ...phase, points: [...phase.points, point] } })
  },

  undoPoint: () => {
    const { phase } = get()
    if (phase.type !== 'drawing') return
    set({ phase: { ...phase, points: phase.points.slice(0, -1) } })
  },

  clearPoints: () => {
    const { phase } = get()
    if (phase.type !== 'drawing') return
    set({ phase: { ...phase, points: [] } })
  },

  finishLine: (line) => {
    const { phase } = get()
    if (phase.type !== 'drawing') return
    set({ phase: { type: 'confirmSplit', fid: phase.fid, rowVersion: phase.rowVersion, line } })
  },

  redrawLine: () => {
    const { phase } = get()
    if (phase.type !== 'confirmSplit') return
    set({ phase: { type: 'drawing', fid: phase.fid, rowVersion: phase.rowVersion, points: [] } })
  },

  openMerge: () => set({ phase: { type: 'merge' } }),

  cancel: () => set({ phase: IDLE }),
}))

/**
 * Whether the split line is being drawn right now.
 *
 * Read by the workspace to stand Identify down: a click that places a vertex must not
 * also open an attribute popup, the same conflict measuring and the rectangle tool have
 * with it.
 */
export function useIsDrawingSplitLine(): boolean {
  return useStructure((state) => state.phase.type === 'drawing')
}
