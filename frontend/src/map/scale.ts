/**
 * Rounds a distance down to a "nice" 1/2/5 * 10^n value -- the same rule Leaflet's
 * and OpenLayers' scale controls use, so the bar always reads a round number
 * instead of an arbitrary one like "73 m".
 *
 * Strictly down, never to the nearest step. Rounding 430 m up to 500 m would make the
 * bar 16% wider than the width it was given, which is not a cosmetic overshoot: on the
 * exported image the bar shares its edge with the attribution notice, and on screen it
 * shares it with the coordinate readout.
 */
export function niceDistance(maxMeters: number): number {
  if (maxMeters <= 0) return 0
  const exponent = Math.floor(Math.log10(maxMeters))
  const fraction = maxMeters / 10 ** exponent
  const niceFraction = fraction >= 5 ? 5 : fraction >= 2 ? 2 : 1
  return niceFraction * 10 ** exponent
}

export interface ScaleBar {
  widthPx: number
  label: string
}

/**
 * Web Mercator metres per CSS pixel at a given latitude and zoom: resolution halves with
 * every zoom level and shrinks with cos(latitude) away from the equator.
 *
 * The constant is the earth's circumference divided by MapLibre's tile size, and that
 * tile size is 512, not 256 (`Transform.worldSize` is `tileSize * 2^zoom`). Leaflet's and
 * Google's tables are built on 256 px tiles and give 156543 m/px at zoom 0 -- using their
 * number here makes every distance come out twice as long as it is. Measured against
 * `map.unproject` at zoom 17 in Hamburg: 0.3537 m/px on the map, 0.3541 by this formula,
 * 0.7082 by the 256 px one.
 *
 * The remaining tenth of a percent is the difference between the sphere this formula
 * assumes and the ellipsoid `unproject().distanceTo()` measures on -- far below the
 * rounding the bar's label does anyway.
 */
export function metersPerPixel(latitude: number, zoom: number): number {
  return (78271.51696 * Math.cos((latitude * Math.PI) / 180)) / 2 ** zoom
}

/** Bar width and label for a scale control, capped at `maxWidthPx`. */
export function computeScaleBar(latitude: number, zoom: number, maxWidthPx = 100): ScaleBar {
  const mpp = metersPerPixel(latitude, zoom)
  const meters = niceDistance(maxWidthPx * mpp)
  const widthPx = mpp > 0 ? meters / mpp : 0
  const label = meters >= 1000 ? `${meters / 1000} km` : `${meters} m`
  return { widthPx, label }
}
