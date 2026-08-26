import { useEffect, useRef } from 'react'
import { useMap } from './MapContext'

export interface ViewportRequest {
  /** [lng, lat] in EPSG:4326, as `ProjectDetail.center` carries it. */
  center: [number, number]
  zoom: number
  /**
   * Changes on every request -- the same trick `ZoomToExtent`'s own `ZoomRequest` uses.
   * A second remote change arriving while the first has not settled yet must replace
   * the target, not be lost, and a value that differs is what makes the effect below
   * run again for it.
   */
  nonce: number
}

/** How long the `easeTo` runs. Long enough to read as a move, short enough not to lag. */
const EASE_DURATION_MS = 600

/**
 * Renders nothing. Follows a viewport someone else set for this project onto this map --
 * `set_view` over MCP, or another open tab dragging its own (TASKS.md Aufgabe 9).
 *
 * `easeTo`, never `jumpTo`: a jump looks the same as a reload from the person watching,
 * and the whole point of following at all is that they can tell the map moved rather
 * than wondering whether it silently reset (Aufgabe 9, Bedingung 1).
 *
 * Deferred while a human is mid-gesture (Bedingung 2): applying `easeTo` under an active
 * drag or scroll-zoom would fight the input the browser is still delivering, and the
 * view would visibly stutter or snap back once the gesture ends. `movestart`'s own
 * `originalEvent` is what tells a human gesture apart from a programmatic one -- present
 * only when a mouse, touch or wheel event actually started the move, so this component's
 * own `easeTo` calls never set the flag they themselves check.
 */
export function RemoteViewport({ request }: { request: ViewportRequest | null }) {
  const { mapRef, isLoaded } = useMap()
  const interacting = useRef(false)

  // Tracks the gesture, independent of any particular request -- a human can start
  // dragging before, during or after a remote viewport arrives, and this has to know
  // the answer whenever the effect below is about to ask it.
  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    function onMoveStart(event: { originalEvent?: unknown }) {
      if (event.originalEvent) interacting.current = true
    }
    function onMoveEnd() {
      interacting.current = false
    }

    map.on('movestart', onMoveStart)
    map.on('moveend', onMoveEnd)
    return () => {
      map.off('movestart', onMoveStart)
      map.off('moveend', onMoveEnd)
    }
  }, [mapRef, isLoaded])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded || !request) return

    function apply() {
      mapRef.current?.easeTo({ center: request!.center, zoom: request!.zoom, duration: EASE_DURATION_MS })
    }

    if (interacting.current) {
      // Waits out the gesture rather than applying now -- the next `moveend` is the
      // human letting go, whatever the map is at when that happens is what the ease
      // starts from. Superseded cleanly if a newer request replaces this one first
      // (the effect cleanup below removes exactly this listener, not a later one's).
      map.once('moveend', apply)
      return () => {
        map.off('moveend', apply)
      }
    }
    apply()
    return undefined
  }, [mapRef, isLoaded, request])

  return null
}
