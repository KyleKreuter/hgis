import { createContext, use, type RefObject } from 'react'
import type { Map as MapLibreMap } from 'maplibre-gl'
import type { AttributionPart } from './basemap'

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
  /**
   * The licence notice the map is currently showing -- the background map's own, plus one
   * run per visible Geoportal layer, already combined by `MapCanvas`.
   *
   * Here rather than assembled a second time by whoever needs it: the notice on the image
   * export has to be the same notice the screen carries, and two copies of that rule
   * would be two chances to credit the wrong provider.
   */
  attribution: readonly AttributionPart[]
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
