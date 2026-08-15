import { useEffect } from 'react'
import { useMap } from './MapContext'
import { PLACE_MARKER_LAYER_ID, raiseOverlays } from './overlays'

const SOURCE_ID = 'hgis-place-marker'
// Shared with `overlays.ts`, which recognises this exact id and keeps it above the
// data layers whenever the catalog is reconciled -- see `SelectionHighlight` for the
// same arrangement.
const LAYER_ID = PLACE_MARKER_LAYER_ID

function emptyCollection(): GeoJSON.FeatureCollection {
  return { type: 'FeatureCollection', features: [] }
}

/**
 * Renders nothing into React. Marks the place the search field last flew to.
 *
 * Same visual language as `SelectionHighlight`'s point case and `editing/SnapMarker` --
 * dark fill, light halo -- but a size of its own (8 rather than 5) so a search hit does
 * not read as an ordinary feature selection sitting on the same layer underneath it.
 */
export function PlaceMarker({ position }: { position: [number, number] | null }) {
  const { mapRef, isLoaded } = useMap()

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    if (!map.getSource(SOURCE_ID)) {
      map.addSource(SOURCE_ID, { type: 'geojson', data: emptyCollection() })
      map.addLayer({
        id: LAYER_ID,
        type: 'circle',
        source: SOURCE_ID,
        paint: {
          'circle-radius': 8,
          'circle-color': '#0f172a',
          'circle-stroke-width': 2.5,
          'circle-stroke-color': '#fafafa',
        },
      })
    }

    const source = map.getSource(SOURCE_ID) as
      | { setData: (data: GeoJSON.FeatureCollection) => void }
      | undefined
    if (source?.setData) {
      source.setData(
        position
          ? {
              type: 'FeatureCollection',
              features: [
                { type: 'Feature', geometry: { type: 'Point', coordinates: position }, properties: {} },
              ],
            }
          : emptyCollection(),
      )
    }

    // A fresh addLayer lands on top of everything, including a running measurement or
    // the current selection highlight -- the same shared rule those two already follow.
    if (position) raiseOverlays(map)
  }, [mapRef, isLoaded, position])

  useEffect(() => {
    return () => {
      // oxlint-disable-next-line react-hooks/exhaustive-deps
      const map = mapRef.current
      if (!map) return
      try {
        if (map.getLayer(LAYER_ID)) map.removeLayer(LAYER_ID)
        if (map.getSource(SOURCE_ID)) map.removeSource(SOURCE_ID)
      } catch {
        // Nothing to clean up if the style is already gone.
      }
    }
  }, [mapRef])

  return null
}
