/**
 * Turns a place-search hit -- a bare point, EPSG:4326 (CONTRACT.md "Ortssuche") -- into
 * an extent `ZoomToExtent` can fly to.
 *
 * Handing the point straight through as `[lng, lat, lng, lat]` would leave the framing
 * entirely to `ZoomToExtent`'s own `maxZoom: 17` clamp, which exists there for a
 * different case (a layer that happens to hold a single point) and says nothing about
 * how far out a street or an Ortsteil hit should sit. A small buffer decides that here
 * instead, on purpose, rather than by accident of a shared clamp: the result lands the
 * user a short pan from their own search term, with enough of the surroundings on
 * screen to see what is next to it.
 */

/** Half-width of the box around a hit, in metres. */
const NEIGHBOURHOOD_RADIUS_METERS = 300

/**
 * Metres per degree of latitude, WGS84 mean. Good to within 0.5% at any latitude --
 * far tighter than a 300 m box needs, since the box only has to look reasonable, not
 * survey-grade.
 */
const METERS_PER_DEGREE_LAT = 111_320

function toRadians(degrees: number): number {
  return (degrees * Math.PI) / 180
}

/**
 * A box `NEIGHBOURHOOD_RADIUS_METERS` wide in every direction from `(lng, lat)`.
 *
 * The longitude half only: a degree of longitude shrinks with `cos(latitude)` as the
 * meridians converge towards the poles, so the same metre budget spans a wider degree
 * range the further north the hit sits -- Hamburg is far enough north (about 53.5°)
 * that skipping this would draw a box roughly 70% too narrow east-west.
 */
export function placeExtent(lng: number, lat: number): [number, number, number, number] {
  const latDelta = NEIGHBOURHOOD_RADIUS_METERS / METERS_PER_DEGREE_LAT
  const lngDelta = NEIGHBOURHOOD_RADIUS_METERS / (METERS_PER_DEGREE_LAT * Math.cos(toRadians(lat)))
  return [lng - lngDelta, lat - latDelta, lng + lngDelta, lat + latDelta]
}
