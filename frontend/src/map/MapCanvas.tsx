import { useEffect, useRef, useState, type ReactNode } from 'react'
// maplibre-gl v6 ships pure named exports, no default -- `Map` is renamed on
// import since it would otherwise shadow the global/DOM `Map` class.
import { Map as MapLibreMap } from 'maplibre-gl'
import { MapContext } from './MapContext'
import { OSM_ATTRIBUTION, OSM_BASEMAP_STYLE } from './basemap'

export type InitialView =
  | { center: [number, number]; zoom: number }
  /** Used for "fit to the project's layer extent" when there is no saved center/zoom. */
  | { bounds: [[number, number], [number, number]] }

interface MapCanvasProps {
  initialView: InitialView
  children?: ReactNode
}

/**
 * Owns the MapLibre instance. The instance itself never becomes React state (see
 * `MapContext`) -- only `isLoaded` does, so that children which need `addSource`/
 * `addLayer` (MapLibre throws before the style has finished loading) can gate on it.
 *
 * The effect below has an empty dependency array and creates the map exactly once
 * for the lifetime of this component. React 19 StrictMode still mounts it twice in
 * dev (mount -> cleanup -> mount): the ref guard makes a stray extra invocation a
 * no-op, and the cleanup calling `map.remove()` is what makes the reset safe --
 * without it two maps would end up sharing the same container.
 */
export function MapCanvas({ initialView, children }: MapCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapLibreMap | null>(null)
  const [isLoaded, setIsLoaded] = useState(false)
  // Read once inside the effect. The project's saved center/zoom must only seed the
  // map at creation time -- a later change (e.g. from another tab) must not reset it.
  const initialViewRef = useRef(initialView)

  useEffect(() => {
    const container = containerRef.current
    if (!container || mapRef.current) return

    const view = initialViewRef.current
    const map = new MapLibreMap({
      container,
      style: OSM_BASEMAP_STYLE,
      // Our own controls replace the MapLibre defaults (zoom buttons, scale,
      // attribution) -- see the `controls/` components rendered as children.
      attributionControl: false,
      ...('bounds' in view
        ? { bounds: view.bounds, fitBoundsOptions: { padding: 32, animate: false } }
        : { center: view.center, zoom: view.zoom }),
    })

    mapRef.current = map
    map.on('load', () => setIsLoaded(true))

    return () => {
      map.remove()
      mapRef.current = null
      setIsLoaded(false)
    }
  }, [])

  return (
    <MapContext value={{ mapRef, isLoaded }}>
      <div className="relative h-full w-full overflow-hidden">
        {/*
          MapLibre takes ownership of this element's contents and, via its own
          stylesheet (loaded after Tailwind on purpose), forces `position: relative`
          on it -- so it must be sized with plain h-full/w-full, never
          `absolute inset-0` on this particular div: inset only has an effect on
          absolutely/fixed positioned boxes, and MapLibre's override silently
          drops it, collapsing the container to zero height.
          Overlay controls below are siblings, positioned against the wrapper. */}
        <div ref={containerRef} className="h-full w-full" />
        {children}
        <span className="pointer-events-none absolute right-1.5 bottom-1 z-10 rounded bg-background/70 px-1 text-[0.625rem] text-muted-foreground">
          {OSM_ATTRIBUTION}
        </span>
      </div>
    </MapContext>
  )
}
