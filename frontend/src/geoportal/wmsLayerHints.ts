/**
 * What the picker tells the user about a WMS layer before they choose it (plan Stufe 4,
 * "Zeig dabei ehrlich an, was der Dienst kann") -- pulled out of `MapImageSection` so the
 * formatting rules have a test of their own, independent of the component's DOM.
 */

import { describeZoomWindow } from '@/layers/zoomWindow'

/**
 * The scale window a service declares for one layer, as one line -- or `null` when the
 * service names neither bound, which is most of them (wms-api-vertrag.md: "minScale/
 * maxScale sind die Maßstabsnenner des Dienstes, oder null").
 *
 * `minScale` is the *smaller* scale denominator (contract wording: "Maßstabsnenner"),
 * i.e. the most zoomed-in bound a WMS `MinScaleDenominator` ever names -- so it reads as
 * "ab" (from here on, zooming out) and `maxScale` as "bis" (up to here, zooming in),
 * matching how a GetCapabilities document orders the two.
 */
export function formatWmsScaleLimits(minScale: number | null, maxScale: number | null): string | null {
  if (minScale === null && maxScale === null) return null
  const denominator = (value: number) => `1:${Math.round(value).toLocaleString('de-DE')}`
  if (minScale !== null && maxScale !== null) return `${denominator(minScale)} – ${denominator(maxScale)}`
  if (minScale !== null) return `ab ${denominator(minScale)}`
  return `bis ${denominator(maxScale as number)}`
}

/** In this order, because transparency decides it -- see `preferredImageFormat`. */
const FORMAT_PREFERENCE = ['image/png', 'image/png32', 'image/png24', 'image/png8', 'image/webp', 'image/jpeg']

/**
 * Which GetMap format to ask a service for.
 *
 * Not simply the first one it offers. Hamburg's `HH_WMS_Fachdaten_ALKIS` lists
 * `image/bmp` ahead of `image/jpeg`, `image/tiff` and finally `image/png` -- and a
 * bitmap tile comes back `200 OK` while MapLibre draws nothing at all, because a raster
 * tile has to pass the browser's image decoder. The map stays white, no error anywhere.
 * The backend already drops the formats no browser can draw; this picks among what is
 * left, and prefers PNG because a map image is usually an overlay and JPEG carries no
 * transparency.
 *
 * Falls back to `image/png` for an empty list -- unreachable through the API (the
 * backend rejects such a service with 422), but a caller with a stale response must not
 * end up sending an empty `FORMAT`.
 */
export function preferredImageFormat(offered: readonly string[]): string {
  return FORMAT_PREFERENCE.find((candidate) => offered.includes(candidate)) ?? offered[0] ?? 'image/png'
}

/**
 * What to add to the "hinzugefügt" toast when the layer will not be on screen yet, or
 * null when it will be.
 *
 * A service's own scale limits become the layer's zoom window, and a detail layer's
 * window can start well past where the user is standing: Hamburg's ALKIS-Festlegungen
 * start at zoom 16, and a project opens at 9.8. The layer is then added correctly,
 * charged to the layer tree, and draws nothing -- which is indistinguishable from an
 * import that failed. Saying it outright costs one sentence.
 *
 * Delegates to `describeZoomWindow`, which the layer tree's own eye badge reads too:
 * the toast and the badge must never disagree about whether a layer is on screen.
 */
export const zoomWindowHint = describeZoomWindow
