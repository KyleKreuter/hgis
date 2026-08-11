import { create } from 'zustand'
import type { LngLat } from './geodesy'
import {
  IDLE,
  addVertex,
  clearSketch,
  exitMeasuring,
  finishSketch,
  moveCursor,
  selectMode,
  undoVertex,
  type MeasureMode,
  type MeasurementState,
} from './session'

interface MeasurementStore extends MeasurementState {
  selectMode: (mode: MeasureMode) => void
  exit: () => void
  clear: () => void
  addVertex: (point: LngLat) => void
  undoVertex: () => void
  moveCursor: (point: LngLat | null) => void
  finish: () => void
}

/**
 * The measuring session, as a store rather than as route state.
 *
 * The pointer position is part of it and changes on every mouse move. Held in the
 * workspace route that would re-render the layer tree and the attribute table sixty
 * times a second; held here, only what actually reads the cursor -- the map overlay
 * and the readout -- re-renders, and the toolbar sees nothing but the mode change.
 *
 * Purely local: a measurement is a question asked of the map, not a fact about the
 * project, so nothing here is persisted or sent anywhere.
 */
export const useMeasurement = create<MeasurementStore>((set) => ({
  ...IDLE,

  selectMode: (mode) => set((state) => selectMode(state, mode)),
  exit: () => set(exitMeasuring()),
  clear: () => set((state) => clearSketch(state)),
  addVertex: (point) => set((state) => addVertex(state, point)),
  undoVertex: () => set((state) => undoVertex(state)),
  moveCursor: (point) => set((state) => moveCursor(state, point)),
  finish: () => set((state) => finishSketch(state)),
}))

/** True while either measuring tool is armed. Used to keep Identify out of the way. */
export function useIsMeasuring(): boolean {
  return useMeasurement((state) => state.mode !== null)
}

/**
 * Ends a running measurement immediately, from outside React.
 *
 * Called when the editing session starts. Doing it through an effect instead -- the
 * toolbar's `disabled` prop -- ends the measurement one commit too late: the drawing
 * tool is mounted by then, and the measuring teardown that follows undoes settings the
 * drawing tool has already made (the double-click zoom above all). Ending it here, in
 * the same synchronous call that switches the mode on, keeps the order plain.
 */
export function endMeasurement(): void {
  useMeasurement.getState().exit()
}
