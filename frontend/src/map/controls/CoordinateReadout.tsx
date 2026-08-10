import { useEffect, useState } from 'react'
import type { MapMouseEvent } from 'maplibre-gl'
import { useMap } from '../MapContext'

const coordinateFormat = new Intl.NumberFormat('de-DE', {
  minimumFractionDigits: 5,
  maximumFractionDigits: 5,
})

/** Cursor position under the mouse, in EPSG:4326 -- always lat/lng, matching the app's axis-order convention (contract 5.3). */
export function CoordinateReadout() {
  const { mapRef, isLoaded } = useMap()
  const [coords, setCoords] = useState<{ lng: number; lat: number } | null>(null)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    const handleMove = (event: MapMouseEvent) => setCoords({ lng: event.lngLat.lng, lat: event.lngLat.lat })
    const handleLeave = () => setCoords(null)

    map.on('mousemove', handleMove)
    map.on('mouseout', handleLeave)
    return () => {
      map.off('mousemove', handleMove)
      map.off('mouseout', handleLeave)
    }
  }, [mapRef, isLoaded])

  return (
    <div className="rounded bg-background/70 px-1.5 py-0.5 font-mono text-[0.625rem] tabular-nums text-muted-foreground">
      {coords ? `${coordinateFormat.format(coords.lat)}, ${coordinateFormat.format(coords.lng)}` : '—, — (EPSG:4326)'}
    </div>
  )
}
