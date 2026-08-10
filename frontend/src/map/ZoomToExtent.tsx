import { useEffect } from 'react'
import { useMap } from './MapContext'

export interface ZoomRequest {
  /** [minLng, minLat, maxLng, maxLat] in EPSG:4326, as `LayerSummary.extent` carries it. */
  extent: [number, number, number, number]
  /**
   * Changes on every request. Zooming to the same layer twice is a legitimate wish --
   * after panning away -- and without a value that differs, the effect below would not
   * run a second time.
   */
  nonce: number
}

/**
 * Renders nothing. Flies the map to a requested extent.
 *
 * The layer tree lives in the left dock, outside the map, and therefore outside
 * `MapContext`. Rather than lifting the map instance into a global store for this one
 * interaction, the request travels down as a prop and is applied here, inside the
 * canvas, where `useMap()` works.
 */
export function ZoomToExtent({ request }: { request: ZoomRequest | null }) {
  const { mapRef, isLoaded } = useMap()

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded || !request) return

    const [minLng, minLat, maxLng, maxLat] = request.extent
    map.fitBounds(
      [
        [minLng, minLat],
        [maxLng, maxLat],
      ],
      // A layer of a single point collapses to a zero-size box, which fitBounds would
      // answer with maximum zoom -- maxZoom keeps that at a scale that still shows
      // where the point actually is.
      { padding: 48, maxZoom: 17, duration: 600 },
    )
  }, [mapRef, isLoaded, request])

  return null
}
