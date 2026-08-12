import { create } from 'zustand'

/** [minLng, minLat, maxLng, maxLat] in EPSG:4326. */
export type Bbox = [number, number, number, number]

interface MapViewportState {
  bbox: Bbox | null
  setBbox: (bbox: Bbox) => void
}

/**
 * The map's current viewport, as one fact both the map tree and components outside it
 * can read.
 *
 * `useMap()` only works inside `<MapCanvas>` (`MapContext.tsx`), so the Geoportal
 * dialog -- mounted as a sibling of `<ProjectMap>` in the workspace route, the same way
 * `ImportDialog` is -- has no way to ask the live map instance for its bounds directly.
 * `MapViewportTracker` is the one piece that actually calls `useMap()` and writes the
 * bounds in here on every `moveend`; everything else just reads this store, the same
 * split `useSelection`/`useRectangleSelect` already use for map state read from outside
 * the map tree.
 */
export const useMapViewport = create<MapViewportState>((set) => ({
  bbox: null,
  setBbox: (bbox) => set({ bbox }),
}))
