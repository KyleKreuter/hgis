import { useEffect, useState } from 'react'
import { useMap } from '../MapContext'
import { computeScaleBar, type ScaleBar } from '../scale'
import { isTilted } from './compass'

/**
 * Recomputed on every `move` (pan changes latitude, zoom changes resolution, pitch
 * changes whether the "Mitte" qualifier below applies).
 *
 * The bar's own maths stay correct at any pitch without change: `computeScaleBar` reads
 * the map's centre and zoom, and MapLibre defines zoom as a resolution at the centre of
 * the view, which tilting the camera around that same centre does not alter -- measured
 * in the browser at zoom 18, the centre resolution held at 0.1772 m/px from pitch 0
 * through pitch 60, matching the formula to five digits every time. What pitch breaks is
 * the promise a single bar makes everywhere else on screen: resolution coarsens toward
 * the horizon and sharpens toward the camera, so the same bar read off the top or bottom
 * edge of a tilted view is quietly wrong by as much as pitch allows (measured up to
 * roughly 2x at 45 degrees, 5x at the 60 degree ceiling, at the very top edge). A second
 * bar or a gradient would answer that properly but has nowhere to fit in a corner widget
 * this size, so the bar keeps meaning exactly what it always meant -- the centre -- and
 * says so once it stops being everywhere at once.
 */
export function ScaleControl() {
  const { mapRef, isLoaded } = useMap()
  const [bar, setBar] = useState<ScaleBar | null>(null)
  const [tilted, setTilted] = useState(false)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    const update = () => {
      const center = map.getCenter()
      setBar(computeScaleBar(center.lat, map.getZoom()))
      setTilted(isTilted(map.getPitch()))
    }

    update()
    map.on('move', update)
    return () => {
      map.off('move', update)
    }
  }, [mapRef, isLoaded])

  if (!bar || bar.widthPx <= 0) return null

  return (
    <div
      className="flex flex-col items-start gap-0.5 rounded bg-background/70 px-1.5 py-0.5"
      title={tilted ? 'Maßstab gilt für die Kartenmitte. Oben und unten im Bild weicht er ab.' : undefined}
    >
      <div className="h-1.5 border-b-2 border-l-2 border-r-2 border-foreground/60" style={{ width: `${bar.widthPx}px` }} />
      <span className="text-[0.625rem] tabular-nums text-muted-foreground">
        {bar.label}
        {tilted && ' (Mitte)'}
      </span>
    </div>
  )
}
