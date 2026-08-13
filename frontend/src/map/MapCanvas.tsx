import { useEffect, useRef, useState, type ReactNode } from 'react'
// maplibre-gl v6 ships pure named exports, no default -- `Map` is renamed on
// import since it would otherwise shadow the global/DOM `Map` class.
import { Map as MapLibreMap, prewarm } from 'maplibre-gl'
import { MapContext } from './MapContext'
import { buildBasemapStyle, resolveBasemap } from './basemap'
import { applyBasemap, applyBasemapOpacity } from './applyBasemap'
import { combinedAttributionParts, type GeoportalAttributionEntry } from './geoportalAttribution'
import { releaseWebGl } from './releaseWebGl'

export type InitialView =
  | { center: [number, number]; zoom: number }
  /** Used for "fit to the project's layer extent" when there is no saved center/zoom. */
  | { bounds: [[number, number], [number, number]] }

interface MapCanvasProps {
  initialView: InitialView
  /**
   * The background map to show, already resolved by the caller (the active layer's own
   * basemap if it has one, the project's otherwise -- see `resolveBasemapSettings`).
   * Unknown values fall back to OSM, and leaving it out (a map outside a project) does
   * the same. `MapCanvas` itself knows nothing about layers or projects.
   */
  basemapId?: string | null
  /** The background map's opacity, already resolved the same way. Defaults to full. */
  basemapOpacity?: number
  /**
   * Licence notices to credit alongside the basemap's own attribution (CONTRACT.md
   * phase 23, section 11.7) -- already reduced to one entry per distinct attribution
   * among the *visible* Geoportal layers by the caller (`ProjectMap`, via
   * `distinctVisibleAttributions`). `MapCanvas` itself still knows nothing about layers.
   */
  geoportalAttributions?: GeoportalAttributionEntry[]
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
export function MapCanvas({
  initialView,
  basemapId,
  basemapOpacity = 1,
  geoportalAttributions = [],
  children,
}: MapCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapLibreMap | null>(null)
  const [isLoaded, setIsLoaded] = useState(false)
  // Read once inside the effect. The project's saved center/zoom must only seed the
  // map at creation time -- a later change (e.g. from another tab) must not reset it.
  const initialViewRef = useRef(initialView)
  // Same reasoning for the basemap, but for a different reason: the constructor style
  // must be the stored one so the map never starts on OSM and then swaps. Every later
  // change is handled by the effects below, on the live map.
  const initialBasemapRef = useRef(basemapId)
  const initialOpacityRef = useRef(basemapOpacity)
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
      style: buildBasemapStyle(initialBasemapRef.current, initialOpacityRef.current),
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
        // remove() gave up before it released the drawing buffer, so the context is
        // still alive with nothing left to draw it. A browser keeps only a handful --
        // Firefox around 16 -- and refuses the next one rather than freeing an old one,
        // which surfaces as MapLibre's "WebGL2 is required to display this map" on a
        // machine that supports it perfectly well. In dev this path runs on every
        // StrictMode cycle and every hot reload, so the count is spent in reloads, not
        // in days. Losing it by hand is the only way back.
        releaseWebGl(container)
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

  // Independent of the swap above on purpose (CONTRACT.md): the opacity can change
  // while the same basemap stays on screen, e.g. dragging the slider in `BasemapControl`,
  // and `applyBasemap` must keep its early return for that case or the map would flash
  // on every unrelated render. Runs after a swap too (same dependency array plus
  // `basemap`), since `addLayer` above does not carry the current opacity on its own.
  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return
    applyBasemapOpacity(map, basemapOpacity)
  }, [basemap, basemapOpacity, isLoaded])

  const attributionParts = combinedAttributionParts(basemap.attribution, geoportalAttributions)

  return (
    <MapContext value={{ mapRef, isLoaded, attribution: attributionParts }}>
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
        {children}
        {/* Follows the selection: the OSM notice must not stay up over OpenTopoMap's
            tiles, and "no basemap" credits nobody -- there is nothing to credit. A
            visible Geoportal layer earns its own credit here regardless (CONTRACT.md
            phase 23), which is why the box can appear even for "Keine Hintergrundkarte".
            The box itself stays transparent to the pointer so a drag that starts over
            it still pans the map; only the links themselves take clicks, which is the
            minimum a licence asking to be linked can be given. */}
        {attributionParts.length > 0 && (
          /*
           * The width cap keeps the notice clear of the scale bar and the coordinate
           * readout, which sit on the same bottom edge at the other end (`MapControls`).
           * At 80% it ran straight over them as soon as the map was narrow -- and on a
           * map with a Geoportal layer the text is long enough for that to be the normal
           * case, not an edge one. 17rem is what those two controls need at their widest:
           * a 100px scale bar and a full lat/lng pair, each in its own padded box, plus
           * the 1.5 they are inset by. Wrapping to another line costs nothing here -- the
           * box grows upwards, into the map, where there is always room. On a wide map
           * the cap is the same 80% it was before, so nothing moves there.
           */
          <p className="pointer-events-none absolute right-1.5 bottom-1 z-10 max-w-[calc(100%-17rem)] rounded bg-background/70 px-1 text-right text-[0.625rem] leading-4 text-muted-foreground">
            {attributionParts.map((part, index) =>
              part.href ? (
                <a
                  // Index, not the text: two Geoportal credits can share the exact same
                  // separator or closing paren, and the text alone would collide.
                  key={index}
                  href={part.href}
                  target="_blank"
                  rel="noreferrer"
                  className="pointer-events-auto underline underline-offset-2 hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
                >
                  {part.text}
                </a>
              ) : (
                <span key={index}>{part.text}</span>
              ),
            )}
          </p>
        )}
      </div>
    </MapContext>
  )
}
