import { createContext, use, type RefObject } from 'react'
import type { Map as MapLibreMap } from 'maplibre-gl'

export interface MapContextValue {
  /**
   * The map instance lives in a ref, never in state -- it is an imperative handle,
   * not a value whose changes should trigger a re-render. Consumers that need to
   * react to the map existing use `isLoaded` instead.
   */
  mapRef: RefObject<MapLibreMap | null>
  /**
   * True once MapLibre's `load` event has fired. Anything calling `addSource` /
   * `addLayer` (MapLayerSync, or later panels) must wait for this -- MapLibre
   * throws if the style is not done loading yet.
   */
  isLoaded: boolean
}

export const MapContext = createContext<MapContextValue | null>(null)

/** Throws outside `<MapCanvas>` on purpose -- every map child needs both the ref and isLoaded. */
export function useMap(): MapContextValue {
  const value = use(MapContext)
  if (!value) {
    throw new Error('useMap must be used within <MapCanvas>')
  }
  return value
}
