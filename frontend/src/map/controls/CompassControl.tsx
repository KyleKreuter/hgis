import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { useMap } from '../MapContext'
import { compassLabel, isTilted, normalizeBearing, pitchFraction } from './compass'

/**
 * Needle and tilt readout, and the way back to north-up and flat.
 *
 * hgis never switched rotation off, so MapLibre's defaults apply and every map can be
 * turned with Ctrl-drag and tilted to 60 degrees. Until now nothing said so: a user who
 * turned the map by accident saw a wrong-looking map and no way back. This control is
 * that way back, and the needle is the notice that something is off north.
 *
 * Always mounted rather than appearing only once the map is turned -- a button that
 * materializes would shift the zoom buttons under the cursor at the very moment the user
 * is dragging.
 */
export function CompassControl() {
  const { mapRef, isLoaded } = useMap()
  const [bearing, setBearing] = useState(0)
  const [pitch, setPitch] = useState(0)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    const update = () => {
      setBearing(map.getBearing())
      setPitch(map.getPitch())
    }

    update()
    // Both, not just `rotate`: a tilt fires `pitch` alone, and the readout has to follow
    // it or a tilted map would look flat in here.
    map.on('rotate', update)
    map.on('pitch', update)
    return () => {
      map.off('rotate', update)
      map.off('pitch', update)
    }
  }, [mapRef, isLoaded])

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      className="relative rounded-none border-b"
      aria-label={compassLabel(bearing, pitch)}
      title={compassLabel(bearing, pitch)}
      onClick={() => mapRef.current?.easeTo({ bearing: 0, pitch: 0, duration: 400 })}
    >
      <svg viewBox="0 0 24 24" className="size-4" aria-hidden="true">
        <g
          style={{
            // Counter-rotating the needle is what makes it point at true north while the
            // map turns underneath it.
            transform: `rotate(${-normalizeBearing(bearing)}deg)`,
            transformOrigin: 'center',
          }}
        >
          <polygon points="12,3 15.5,13 12,11 8.5,13" className="fill-destructive" />
          <polygon points="12,21 8.5,11 12,13 15.5,11" className="fill-muted-foreground/50" />
        </g>
      </svg>
      {/*
        The tilt has no place on a needle -- it is the other axis. A bar under the icon
        carries it instead, growing with the angle, and it stays out of the way entirely
        while the map is flat.
      */}
      {isTilted(pitch) && (
        <span
          data-testid="pitch-bar"
          className="pointer-events-none absolute bottom-0.5 left-1/2 h-0.5 -translate-x-1/2 rounded-full bg-destructive"
          style={{ width: `${pitchFraction(pitch, mapRef.current?.getMaxPitch() ?? 60) * 14}px` }}
        />
      )}
    </Button>
  )
}
