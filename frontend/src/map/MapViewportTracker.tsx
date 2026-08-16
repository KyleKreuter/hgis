import { useEffect } from 'react'
import { useMap } from './MapContext'
import { useMapViewport } from './mapViewportStore'
import { isPitchExpanded } from './viewportBounds'

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
  const setViewport = useMapViewport((state) => state.setViewport)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    function report() {
      const target = mapRef.current
      if (!target) return
      // Plain `getBounds()`, deliberately: it always contains everything on screen, at
      // any pitch, and a bbox filter must never drop something the user can see. See
      // `isPitchExpanded` for what happens instead of shrinking it.
      const bounds = target.getBounds()
      const bbox: [number, number, number, number] = [
        bounds.getWest(),
        bounds.getSouth(),
        bounds.getEast(),
        bounds.getNorth(),
      ]
      const canvas = target.getCanvas()
      const center = target.getCenter()
      const zoom = target.getZoom()
      const expanded = isPitchExpanded({
        bbox,
        width: canvas.clientWidth,
        height: canvas.clientHeight,
        center: [center.lng, center.lat],
        zoom,
      })
      setViewport(bbox, zoom, expanded)
    }

    report()
    // `zoomend` is not enough on its own -- a zoom always ends in a `moveend` too, but a
    // pure pan never fires `zoomend`, and the bbox has to follow both.
    map.on('moveend', report)
    return () => {
      map.off('moveend', report)
    }
  }, [mapRef, isLoaded, setViewport])

  return null
}
