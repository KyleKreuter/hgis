import { useEffect } from 'react'
import { useMap } from './MapContext'
import { useMapViewport } from './mapViewportStore'

/**
 * Renders nothing. Keeps `useMapViewport`'s bbox in step with the live map, so the
 * Geoportal dialog's "aktueller Kartenausschnitt" toggle (plan 6.5, Schritt 5) can read
 * what the user is currently looking at without being mounted inside the map tree
 * itself -- see `mapViewportStore.ts` for why that split exists.
 *
 * Reports once on load, not only on `moveend`: without that, opening the dialog before
 * the user has ever panned the map would find no bbox at all.
 */
export function MapViewportTracker() {
  const { mapRef, isLoaded } = useMap()
  const setBbox = useMapViewport((state) => state.setBbox)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    function report() {
      const target = mapRef.current
      if (!target) return
      const bounds = target.getBounds()
      setBbox([bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()])
    }

    report()
    map.on('moveend', report)
    return () => {
      map.off('moveend', report)
    }
  }, [mapRef, isLoaded, setBbox])

  return null
}
