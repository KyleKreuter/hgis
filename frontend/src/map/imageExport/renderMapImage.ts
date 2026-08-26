/**
 * Renders the map into a PNG (CONTRACT.md 13.2) on a second, hidden MapLibre map.
 *
 * The visible map is never touched. Reading pixels out of a WebGL canvas needs
 * `preserveDrawingBuffer`, and that option costs every user a copy of the frame buffer on
 * every frame -- including the great majority who never export anything. A throwaway map
 * that carries the flag only for the seconds it lives keeps that cost where it belongs.
 *
 * The hidden map is built from the visible one's own style, so it shows the same
 * background map, the same layers in the same order, and the same visibility. What is on
 * screen is what lands in the file.
 */

import { Map as MapLibreMap } from 'maplibre-gl'
import type { LayerSummary } from '@/api/layers'
import type { AttributionPart } from '../basemap'
import { drawFurniture } from './drawFurniture'
import { buildFurniture } from './furniture'
import { exportZoom } from './exportView'
import { describeImageSize, type ImageSize } from './pageFormat'
import { maxCanvasSizeFor } from './renderLimit'
import { sharpenRasterSources } from './sharpenRaster'
import { releaseWebGl } from '../releaseWebGl'

/**
 * A guard against a map that never finishes, not a substitute for `idle`: it can only
 * end the export with an error, never hand out a half-drawn image. A tile server that
 * has stopped answering is the case it exists for -- without it the dialog would sit on
 * "Wird erzeugt…" forever.
 */
const IDLE_TIMEOUT_MS = 45_000

export interface MapImageOptions {
  /** The visible map. Read only: its style, camera and container size. */
  source: MapLibreMap
  /** User-typed; blank means no title on the image. */
  title: string
  size: ImageSize
  /** Basemap plus visible Geoportal layers, already combined by the caller. */
  attribution: readonly AttributionPart[]
  /** From `readMaxRenderbufferSize`; caps MapLibre's own `maxCanvasSize`. */
  maxRenderbufferSize: number | null
  /** Visible layers to include in the legend. */
  layers?: readonly LayerSummary[]
  /** Whether to draw the legend on the image. */
  includeLegend?: boolean
}

export interface MapImageResult {
  blob: Blob
  /**
   * Tile or style failures the hidden map reported while it loaded. The image was still
   * produced -- a single tile that did not answer is not worth throwing an otherwise
   * complete export away -- but the caller has to say so rather than swallow them.
   */
  warnings: string[]
}

/**
 * A hidden box, laid out at the export's CSS size.
 *
 * Inside the viewport and merely transparent, not moved off-screen: `requestAnimationFrame`
 * is what drives MapLibre's rendering, and a browser is free to skip frames for content
 * that is nowhere near the screen. `display: none` would be worse still -- the container
 * would have no size at all, and MapLibre reads `clientWidth`/`clientHeight` to size its
 * canvas.
 */
function createHiddenContainer(size: ImageSize): HTMLDivElement {
  const container = document.createElement('div')
  container.setAttribute('aria-hidden', 'true')
  container.style.cssText = [
    'position: fixed',
    'top: 0',
    'left: 0',
    `width: ${size.cssWidth}px`,
    `height: ${size.cssHeight}px`,
    'opacity: 0',
    'pointer-events: none',
    'z-index: -1',
  ].join('; ')
  document.body.appendChild(container)
  return container
}

/** Resolves once the hidden map has drawn everything it asked for. */
function waitForIdle(map: MapLibreMap, warnings: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const onError = (event: { error?: Error }) => {
      const message = event.error?.message
      if (message && !warnings.includes(message)) warnings.push(message)
    }

    const timer = setTimeout(() => {
      map.off('error', onError)
      reject(
        new Error(
          'Die Karte war nach 45 Sekunden noch nicht vollständig geladen. ' +
            'Versuchen Sie es erneut oder wählen Sie einen kleineren Ausschnitt.',
        ),
      )
    }, IDLE_TIMEOUT_MS)

    map.on('error', onError)
    // Attached in the same tick the map is created in, before the first animation frame
    // can run -- so there is no window in which an `idle` could fire unheard and leave
    // this promise waiting for a second one that never comes.
    map.once('idle', () => {
      clearTimeout(timer)
      map.off('error', onError)
      resolve()
    })
  })
}

function canvasToBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    try {
      canvas.toBlob((blob) => {
        if (blob) resolve(blob)
        else reject(new Error('Das Programm konnte kein Bild aus der Karte erzeugen.'))
      }, 'image/png')
    } catch {
      // A canvas that has touched a tile served without CORS headers is tainted, and
      // reading it back throws a SecurityError. The export ends here with a message
      // rather than with an empty file.
      reject(
        new Error(
          'Eine Hintergrundkarte erlaubt das Auslesen ihrer Kacheln nicht. ' +
            'Wählen Sie eine andere Hintergrundkarte.',
        ),
      )
    }
  })
}

export async function renderMapImage(options: MapImageOptions): Promise<MapImageResult> {
  const { source, size } = options
  const sourceContainer = source.getContainer()
  const screen = { width: sourceContainer.clientWidth, height: sourceContainer.clientHeight }
  const center = source.getCenter()
  const bearing = source.getBearing()
  const pitch = source.getPitch()
  // The export's own zoom, not the screen's -- the page has a different shape. Everything
  // downstream, the scale bar above all, is computed from this value.
  const zoom = exportZoom(source.getZoom(), screen, {
    width: size.cssWidth,
    height: size.cssHeight,
  })

  const warnings: string[] = []
  const container = createHiddenContainer(size)
  // The `finally` below only covers what happens after the map exists. Creating it can
  // fail on its own -- a style that is not loaded yet, a WebGL context the browser
  // refuses -- and the container must not stay behind in the document for that.
  let exportMap: MapLibreMap
  try {
    exportMap = new MapLibreMap({
      container,
      // Raster basemaps do not follow `pixelRatio` on their own -- their tiles are
      // finished images, and more pixels only stretch them. Without this the export puts
      // sharp vector data on a blurry map.
      style: sharpenRasterSources(source.getStyle(), size.pixelRatio),
      center,
      zoom,
      bearing,
      pitch,
      // The whole reason this second map exists: it renders at the page's resolution
      // while laying the map out at the page's CSS size, so labels and line widths keep
      // their physical size instead of shrinking with the pixel count.
      pixelRatio: size.pixelRatio,
      // MapLibre caps the canvas at 4096 px by default and enforces the cap by lowering
      // the pixel ratio without a word. Raising it to what the hardware really allows is
      // what keeps A3 at 300 dpi from coming out quietly softer than asked for.
      maxCanvasSize: maxCanvasSizeFor(size, options.maxRenderbufferSize),
      canvasContextAttributes: { preserveDrawingBuffer: true },
      attributionControl: false,
      interactive: false,
      // Label fades are for a map in motion. On a single still frame they can only mean
      // half-drawn labels, so they are switched off rather than waited out.
      fadeDuration: 0,
    })
  }
  catch (caught) {
    // The constructor can fail after it has already taken a context -- it is the setup
    // that follows which throws. Dropping the container alone would strand that context.
    releaseWebGl(container)
    container.remove()
    throw caught
  }

  try {
    await waitForIdle(exportMap, warnings)

    const mapCanvas = exportMap.getCanvas()
    // `clientWidth` is a whole number, so the canvas may sit a pixel or two off the exact
    // target. Anything below that is MapLibre clamping the pixel ratio after all, and a
    // quietly smaller image is exactly what CONTRACT.md 13.3 refuses to hand out.
    const tolerance = Math.ceil(size.pixelRatio) + 1
    if (mapCanvas.width < size.widthPx - tolerance || mapCanvas.height < size.heightPx - tolerance) {
      throw new Error(
        `Ihre Grafikkarte konnte nur ${mapCanvas.width} × ${mapCanvas.height} Pixel ` +
          `zeichnen statt ${describeImageSize(size)}. Wählen Sie ein kleineres Format ` +
          'oder eine geringere Auflösung.',
      )
    }

    const out = document.createElement('canvas')
    out.width = size.widthPx
    out.height = size.heightPx
    const ctx = out.getContext('2d')
    if (!ctx) throw new Error('Das Programm konnte keine Zeichenfläche für das Bild anlegen.')

    // White first: without a background map the map draws nothing, and a transparent PNG
    // turns black in some viewers and in most print paths.
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, size.widthPx, size.heightPx)
    // Scaled explicitly to the target rather than drawn one to one, which absorbs the
    // pixel or two of rounding above and makes the file exactly the size that was asked
    // for.
    ctx.drawImage(
      mapCanvas,
      0,
      0,
      mapCanvas.width,
      mapCanvas.height,
      0,
      0,
      size.widthPx,
      size.heightPx,
    )

    // The app's own font has long been loaded by the time anyone opens this dialog, but a
    // canvas silently falls back to the system font if it is not -- and the difference
    // would only show up in the file.
    await document.fonts?.ready

    drawFurniture(
      ctx,
      buildFurniture({
        title: options.title,
        centerLat: center.lat,
        zoom,
        bearing,
        pitch,
        cssWidth: size.cssWidth,
        attribution: options.attribution,
        layers: options.layers,
        includeLegend: options.includeLegend,
      }),
      size,
    )

    return { blob: await canvasToBlob(out), warnings }
  } finally {
    // Both, always: a map left behind holds a WebGL context, and a browser refuses the
    // next context once a handful are live -- which would be the visible map's.
    //
    // remove() throws when it runs before WebGL finished initialising, which is exactly
    // what an export that fails early does. Unguarded, that throw would skip
    // `container.remove()` below and leave both the context and the hidden box behind --
    // the very leak this block exists to prevent.
    try {
      exportMap.remove()
    }
    catch (error) {
      console.debug('[hgis] removing the export map:', error)
      releaseWebGl(container)
    }
    container.remove()
  }
}
