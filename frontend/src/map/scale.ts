/**
 * Rounds a distance down to a "nice" 1/2/5 * 10^n value -- the same rule Leaflet's
 * and OpenLayers' scale controls use, so the bar always reads a round number
 * instead of an arbitrary one like "73 m".
 */
export function niceDistance(maxMeters: number): number {
  if (maxMeters <= 0) return 0
  const exponent = Math.floor(Math.log10(maxMeters))
  const fraction = maxMeters / 10 ** exponent
  const niceFraction = fraction < 1.5 ? 1 : fraction < 3.5 ? 2 : fraction < 7.5 ? 5 : 10
  return niceFraction * 10 ** exponent
}

export interface ScaleBar {
  widthPx: number
  label: string
}

/**
 * Web Mercator meters-per-pixel at a given latitude and zoom -- the standard
 * formula (also used by MapLibre/Leaflet/Google Maps internally): resolution
 * halves with every zoom level and shrinks with cos(latitude) away from the equator.
 */
export function metersPerPixel(latitude: number, zoom: number): number {
  return (156543.03392 * Math.cos((latitude * Math.PI) / 180)) / 2 ** zoom
}

/** Bar width and label for a scale control, capped at `maxWidthPx`. */
export function computeScaleBar(latitude: number, zoom: number, maxWidthPx = 100): ScaleBar {
  const mpp = metersPerPixel(latitude, zoom)
  const meters = niceDistance(maxWidthPx * mpp)
  const widthPx = mpp > 0 ? meters / mpp : 0
  const label = meters >= 1000 ? `${meters / 1000} km` : `${meters} m`
  return { widthPx, label }
}
