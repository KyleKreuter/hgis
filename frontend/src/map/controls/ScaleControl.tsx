import { useEffect, useState } from 'react'
import { useMap } from '../MapContext'
import { computeScaleBar, type ScaleBar } from '../scale'

/** Recomputed on every `move` (pan changes latitude, zoom changes resolution). */
export function ScaleControl() {
  const { mapRef, isLoaded } = useMap()
  const [bar, setBar] = useState<ScaleBar | null>(null)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    const update = () => {
      const center = map.getCenter()
      setBar(computeScaleBar(center.lat, map.getZoom()))
    }

    update()
    map.on('move', update)
    return () => {
      map.off('move', update)
    }
  }, [mapRef, isLoaded])

  if (!bar || bar.widthPx <= 0) return null

  return (
    <div className="flex flex-col items-start gap-0.5 rounded bg-background/70 px-1.5 py-0.5">
      <div className="h-1.5 border-b-2 border-l-2 border-r-2 border-foreground/60" style={{ width: `${bar.widthPx}px` }} />
      <span className="text-[0.625rem] tabular-nums text-muted-foreground">{bar.label}</span>
    </div>
  )
}
