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
 *
 * Just not, on its own, *why*: nothing in reach and "in reach, but too close to the
 * horizon to trust" (`isSnapPrecisionUsable`) both show no marker here. `DrawController`
 * tells the two apart through `onSnapUnavailable` instead, which the toolbar's magnet
 * button and its tooltip read -- this component only ever renders the position, never
 * the reason.
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
          // Weight follows how specific the target is: a vertex is a place the data
          // states outright, a crossing one it implies, a point on an edge merely one it
          // permits. Landing on any of the three is a different result, so they must not
          // look alike -- and weight carries it, the palette being monochrome by
          // convention.
          'circle-radius': [
            'match',
            ['get', 'kind'],
            'vertex', 7,
            'intersection', 6,
            5,
          ],
          'circle-stroke-width': [
            'match',
            ['get', 'kind'],
            'vertex', 2.5,
            'intersection', 2,
            1.5,
          ],
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
      // The rule guards against a ref to a React-rendered node, which can be gone by
      // cleanup time. This one holds the MapLibre instance, and the current one is
      // exactly what has to be cleaned up.
      // oxlint-disable-next-line react-hooks/exhaustive-deps
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
