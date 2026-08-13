/**
 * The size ceiling WebGL puts on the export (CONTRACT.md 13.3).
 *
 * Two things make this necessary rather than defensive:
 *
 * - A drawing buffer wider or taller than `MAX_RENDERBUFFER_SIZE` cannot be created at
 *   all. Typically 16384 px, but 4096 px on older integrated graphics -- which A3 at
 *   300 dpi (4961 px) already exceeds.
 * - MapLibre caps the canvas at `maxCanvasSize`, 4096 x 4096 by default, and enforces it
 *   by *quietly lowering the pixel ratio*. Left alone it produces a plausible-looking
 *   image at the wrong resolution, which is precisely the failure nobody would notice.
 *   The export therefore passes the real GPU limit as `maxCanvasSize` and checks the
 *   target against it here, up front.
 */

import { describeImageSize, type ImageSize } from './pageFormat'

/**
 * `gl.MAX_RENDERBUFFER_SIZE` from a throwaway context, or null when WebGL is
 * unavailable.
 *
 * A separate canvas rather than the map's own: `getContext` on a canvas that already has
 * a WebGL context returns that same context only for a matching type, and asking the map
 * for its internals reaches past MapLibre's public API. The probe canvas is one pixel and
 * is dropped right after.
 *
 * Null is not a failure by itself -- a browser without WebGL has no map to export in the
 * first place. It only means this check cannot speak, and the export goes ahead.
 */
export function readMaxRenderbufferSize(): number | null {
  if (typeof document === 'undefined') return null
  const canvas = document.createElement('canvas')
  canvas.width = 1
  canvas.height = 1
  const gl =
    (canvas.getContext('webgl2') as WebGL2RenderingContext | null) ??
    (canvas.getContext('webgl') as WebGLRenderingContext | null)
  if (!gl) return null
  const value = gl.getParameter(gl.MAX_RENDERBUFFER_SIZE) as unknown
  // Some headless and software renderers answer with 0 or a non-number. A limit of zero
  // would refuse every export, so anything unusable is treated as "cannot say".
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return null
  // Losing the context explicitly, rather than waiting for the garbage collector: a
  // browser only keeps a handful of live WebGL contexts and drops the oldest one when
  // that number is passed -- which would be the map's.
  const lose = gl.getExtension('WEBGL_lose_context')
  lose?.loseContext()
  return value
}

/**
 * The refusal message for a target the graphics card cannot render, or null when it can.
 *
 * Names the ceiling as well as the offending size: "zu groß" alone leaves the user
 * guessing which of the two pickers to turn down, and by how much.
 */
export function renderLimitMessage(size: ImageSize, maxSize: number | null): string | null {
  if (maxSize === null) return null
  if (size.widthPx <= maxSize && size.heightPx <= maxSize) return null
  return (
    `Das Bild wäre ${describeImageSize(size)} groß. Ihre Grafikkarte verarbeitet ` +
    `höchstens ${maxSize} × ${maxSize} Pixel. Wählen Sie ein kleineres Format oder eine ` +
    `geringere Auflösung.`
  )
}

/**
 * The value to hand MapLibre as `maxCanvasSize` for a target that already passed
 * `renderLimitMessage`.
 *
 * The GPU limit where it is known, so MapLibre's own 4096 px default cannot silently
 * scale the export down. Where it is not, the target plus a small margin: the container's
 * `clientWidth` is a whole number, so the canvas can land a pixel or two above the exact
 * target, and a cap set to the target alone would lower the pixel ratio for that pixel.
 */
export function maxCanvasSizeFor(size: ImageSize, maxSize: number | null): [number, number] {
  if (maxSize !== null) return [maxSize, maxSize]
  const margin = Math.ceil(size.pixelRatio) + 2
  return [size.widthPx + margin, size.heightPx + margin]
}
