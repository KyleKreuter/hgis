import { useEffect, useRef, useState, type ReactNode } from 'react'
// maplibre-gl v6 ships pure named exports, no default -- `Map` is renamed on
// import since it would otherwise shadow the global/DOM `Map` class.
import { Map as MapLibreMap, prewarm } from 'maplibre-gl'
import { MapContext } from './MapContext'
import { buildBasemapStyle, resolveBasemap } from './basemap'
import { applyBasemap } from './applyBasemap'
import { BasemapControl } from './BasemapControl'

export type InitialView =
  | { center: [number, number]; zoom: number }
  /** Used for "fit to the project's layer extent" when there is no saved center/zoom. */
  | { bounds: [[number, number], [number, number]] }

interface MapCanvasProps {
  initialView: InitialView
  /**
   * The project's stored basemap. Unknown values fall back to OSM, and leaving it out
   * (a map outside a project) does the same.
   */
  basemapId?: string | null
  /** Enables the basemap picker, which persists the choice to that project. */
  projectId?: string
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
export function MapCanvas({ initialView, basemapId, projectId, children }: MapCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapLibreMap | null>(null)
  const [isLoaded, setIsLoaded] = useState(false)
  // Read once inside the effect. The project's saved center/zoom must only seed the
  // map at creation time -- a later change (e.g. from another tab) must not reset it.
  const initialViewRef = useRef(initialView)
  // Same reasoning for the basemap, but for a different reason: the constructor style
  // must be the stored one so the map never starts on OSM and then swaps. Every later
  // change is handled by the effect below, on the live map.
  const initialBasemapRef = useRef(basemapId)
  const basemap = resolveBasemap(basemapId)

  useEffect(() => {
    const container = containerRef.current
    if (!container || mapRef.current) return

    // MapLibre keeps ONE global worker pool for all map instances and tears it down
    // when the last map is removed. React 19 StrictMode's mount/cleanup/mount cycle
    // hits exactly that: the first instance's removal disposes the pool the second
    // instance is already relying on. Vector tiles are parsed in those workers, so
    // they stop loading entirely -- silently, with no error event, while raster tiles
    // (which bypass the workers) keep working and make the map look healthy.
    //
    // prewarm() keeps the pool alive independently of any single map's lifetime.
    prewarm()

    const view = initialViewRef.current
    const map = new MapLibreMap({
      container,
      style: buildBasemapStyle(initialBasemapRef.current),
      // Our own controls replace the MapLibre defaults (zoom buttons, scale,
      // attribution) -- see the `controls/` components rendered as children.
      attributionControl: false,
      ...('bounds' in view
        ? { bounds: view.bounds, fitBoundsOptions: { padding: 32, animate: false } }
        : { center: view.center, zoom: view.zoom }),
    })

    mapRef.current = map
    if (import.meta.env.DEV) {
      // Debug handle. The map is otherwise unreachable from the console (it lives in a
      // ref by design), which makes questions like "did this source actually load?"
      // impossible to answer from outside -- `__hgisMap.isSourceLoaded(id)` and
      // `querySourceFeatures` are what pinned down the worker problem above.
      ;(window as unknown as Record<string, unknown>).__hgisMap = map
    }
    map.on('load', () => setIsLoaded(true))

    // MapLibre reports tile and style failures through this event and nowhere else --
    // without a listener a broken source fails completely silently, and the map simply
    // renders nothing where the data should be.
    map.on('error', (event) => {
      console.error('[hgis] MapLibre error:', event.error?.message ?? event)
    })

    return () => {
      // remove() throws ("can't access property destroy, this.painter is undefined")
      // when the map is torn down before WebGL finished initialising -- exactly what
      // React 19 StrictMode provokes with its mount/cleanup/mount cycle in dev.
      //
      // The throw is harmless in itself, but it aborts the rest of this cleanup: the
      // ref would stay populated, the second mount would hit the guard above and skip
      // creating a map, and what remained was a half-disposed instance that still drew
      // the basemap but could no longer load any tiles. Hence catch and always reset.
      try {
        map.remove()
      }
      catch (error) {
        console.debug('[hgis] map.remove() during teardown:', error)
      }
      mapRef.current = null
      setIsLoaded(false)
    }
  }, [])

  // Swaps only the basemap layers, so the data layers `MapLayerSync` put on the map --
  // and the style's self-hosted glyph URL -- survive the change untouched. `applyBasemap`
  // returns early when the requested basemap is already there, which is what the very
  // first run after `load` does: the constructor built the style from the same id.
  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return
    applyBasemap(map, basemap)
  }, [basemap, isLoaded])

  return (
    <MapContext value={{ mapRef, isLoaded }}>
      {/* A container, so the overlays can react to the width of the map panel rather
          than to the width of the window -- the map is one resizable pane among three,
          and a viewport breakpoint says nothing about how much room it actually has. */}
      <div className="@container relative h-full w-full overflow-hidden">
        {/*
          MapLibre takes ownership of this element's contents and, via its own
          stylesheet (loaded after Tailwind on purpose), forces `position: relative`
          on it -- so it must be sized with plain h-full/w-full, never
          `absolute inset-0` on this particular div: inset only has an effect on
          absolutely/fixed positioned boxes, and MapLibre's override silently
          drops it, collapsing the container to zero height.
          Overlay controls below are siblings, positioned against the wrapper. */}
        <div ref={containerRef} className="h-full w-full" />
        {/* Top right, immediately left of the zoom stack (`right-11` clears its 28px
            column plus a gap): the top left corner is where the measurement readout
            appears, and the two must not sit on top of each other. */}
        {projectId && (
          <div className="absolute top-2 right-11 z-10">
            <BasemapControl projectId={projectId} basemapId={basemapId} />
          </div>
        )}
        {children}
        {/* Follows the selection: the OSM notice must not stay up over OpenTopoMap's
            tiles, and "no basemap" credits nobody -- there is nothing to credit.
            The box itself stays transparent to the pointer so a drag that starts over
            it still pans the map; only the links themselves take clicks, which is the
            minimum a licence asking to be linked can be given. */}
        {basemap.attribution.length > 0 && (
          <p className="pointer-events-none absolute right-1.5 bottom-1 z-10 max-w-[80%] rounded bg-background/70 px-1 text-right text-[0.625rem] leading-4 text-muted-foreground">
            {basemap.attribution.map((part) =>
              part.href ? (
                <a
                  key={part.text}
                  href={part.href}
                  target="_blank"
                  rel="noreferrer"
                  className="pointer-events-auto underline underline-offset-2 hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
                >
                  {part.text}
                </a>
              ) : (
                <span key={part.text}>{part.text}</span>
              ),
            )}
          </p>
        )}
      </div>
    </MapContext>
  )
}
