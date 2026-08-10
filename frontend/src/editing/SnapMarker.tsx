import { useEffect } from 'react'
import { useMap } from '@/map/MapContext'
import type { SnapTarget } from './snapping'

const SOURCE_ID = 'hgis-snap-marker'

/**
 * Renders nothing into React. Marks the coordinate the pointer would snap to.
 *
 * Without it snapping is invisible: the vertex lands somewhere slightly different from
 * where the cursor was, and there is no way to tell whether that was the tool working or
 * a misplaced click. The marker is the feedback that makes it trustworthy -- and its
 * absence says just as clearly that nothing is in range.
 */
export function SnapMarker({ target }: { target: SnapTarget | null }) {
  const { mapRef, isLoaded } = useMap()

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    if (!map.getSource(SOURCE_ID)) {
      map.addSource(SOURCE_ID, { type: 'geojson', data: emptyCollection() })
      map.addLayer({
        id: `${SOURCE_ID}-vertex`,
        type: 'circle',
        source: SOURCE_ID,
        paint: {
          // A vertex gets the heavier ring, a point on an edge the lighter one. Which of
          // the two is about to be hit matters -- landing on a shared corner and landing
          // somewhere along the line between two are different results -- and the
          // difference is carried by weight, since the palette is monochrome by convention.
          'circle-radius': ['case', ['==', ['get', 'kind'], 'vertex'], 7, 5],
          'circle-stroke-width': ['case', ['==', ['get', 'kind'], 'vertex'], 2.5, 1.5],
          'circle-color': 'transparent',
          'circle-stroke-color': '#0f172a',
        },
      })
    }

    const source = map.getSource(SOURCE_ID) as
      | { setData: (data: GeoJSON.FeatureCollection) => void }
      | undefined
    if (source?.setData) {
      source.setData(
        target
          ? {
              type: 'FeatureCollection',
              features: [
                {
                  type: 'Feature',
                  geometry: { type: 'Point', coordinates: target.position },
                  properties: { kind: target.kind },
                },
              ],
            }
          : emptyCollection(),
      )
    }
  }, [mapRef, isLoaded, target])

  useEffect(() => {
    return () => {
      const map = mapRef.current
      if (!map) return
      // Guarded: the map may already be torn down when the editing session ends.
      try {
        if (map.getLayer(`${SOURCE_ID}-vertex`)) map.removeLayer(`${SOURCE_ID}-vertex`)
        if (map.getSource(SOURCE_ID)) map.removeSource(SOURCE_ID)
      } catch {
        // Nothing to clean up if the style is gone.
      }
    }
  }, [mapRef])

  return null
}

function emptyCollection(): GeoJSON.FeatureCollection {
  return { type: 'FeatureCollection', features: [] }
}
