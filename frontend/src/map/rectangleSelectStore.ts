import { create } from 'zustand'
import { endMeasurement } from '@/measurement/store'
import type { SpatialMode } from '@/api/features'

interface RectangleSelectState {
  /** Whether the tool is armed. Mounting `RectangleSelectTool` follows this flag. */
  active: boolean
  /** 'berührt' vs 'vollständig enthalten' -- see the toolbar's segmented control. */
  touchMode: SpatialMode
  /** True while a drawn rectangle's fids are being counted or paged in. */
  loading: boolean

  activate: () => void
  deactivate: () => void
  setTouchMode: (mode: SpatialMode) => void
  setLoading: (loading: boolean) => void
}

/**
 * The rectangle select tool's own state: on/off, the touch mode, and a loading flag.
 *
 * Deliberately not the drawn rectangle itself or the confirm dialog -- both are purely
 * local to `RectangleSelectTool` and never read anywhere else, so keeping them out of
 * this store avoids re-rendering the toolbar on every pixel of a drag.
 */
export const useRectangleSelect = create<RectangleSelectState>((set) => ({
  active: false,
  touchMode: 'intersects',
  loading: false,

  activate: () => {
    // Same reasoning as `useEditSession.start`: measuring and this tool both claim the
    // drag gesture on the map, so a running measurement has to be gone before the
    // rectangle tool starts listening -- not one render later.
    endMeasurement()
    set({ active: true })
  },
  deactivate: () => set({ active: false, loading: false }),
  setTouchMode: (touchMode) => set({ touchMode }),
  setLoading: (loading) => set({ loading }),
}))

/** True while the rectangle tool is armed. Used to keep Identify out of the way. */
export function useIsRectangleSelecting(): boolean {
  return useRectangleSelect((state) => state.active)
}
