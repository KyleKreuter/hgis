import type { PlaceKind } from '@/api/places'

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
 *
 * How wide that buffer is follows the hit's own `kind`: a street and an Ortsteil are
 * areas the user wants to see in context, a house number is one building the user wants
 * to see.
 */

/** Half-width of the box around a hit, in metres. */
const NEIGHBOURHOOD_RADIUS_METERS = 300

/**
 * Half-width for a house-number hit, in metres.
 *
 * A `kind: 'address'` hit names one building, and 300 m answers a different question than
 * the user asked: a Hamburg plot is roughly 15-30 m wide at the street, so a 600 m box
 * leaves the searched house a handful of pixels among a hundred others -- the user then
 * has to find it a second time, by eye, on the map. 200 m across shows the house, its
 * neighbours on both sides and the far side of the street, which is the frame that makes
 * the single hit identifiable without hiding which street it belongs to.
 *
 * Not smaller than that on purpose, for two reasons. `ZoomToExtent` clamps at
 * `maxZoom: 17`, and this buffer sits just under that ceiling: measured in the browser on
 * a 1579x921 window, the same Hamburg point lands at zoom 15.17 as a street and 16.76 as
 * an address -- the full factor of three, with the clamp still out of the way. Much below
 * 100 m the clamp, not this constant, would decide the framing, and the number here would
 * stop meaning anything. And a geocoded house coordinate sits on the building or on the
 * plot centroid depending on the source, so a box tighter than the buildings themselves
 * would promise a precision the coordinate does not carry.
 */
const ADDRESS_RADIUS_METERS = 100

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
 * A box of the radius `kind` calls for, in every direction from `(lng, lat)`.
 *
 * `kind` is optional so a caller that only holds a coordinate still gets the
 * neighbourhood framing this was written for; the search passes the hit's own kind and
 * gets the tighter box for a house number (see {@link ADDRESS_RADIUS_METERS}).
 *
 * The longitude half only: a degree of longitude shrinks with `cos(latitude)` as the
 * meridians converge towards the poles, so the same metre budget spans a wider degree
 * range the further north the hit sits -- Hamburg is far enough north (about 53.5°)
 * that skipping this would draw a box roughly 70% too narrow east-west.
 */
export function placeExtent(lng: number, lat: number, kind?: PlaceKind): [number, number, number, number] {
  const radius = kind === 'address' ? ADDRESS_RADIUS_METERS : NEIGHBOURHOOD_RADIUS_METERS
  const latDelta = radius / METERS_PER_DEGREE_LAT
  const lngDelta = radius / (METERS_PER_DEGREE_LAT * Math.cos(toRadians(lat)))
  return [lng - lngDelta, lat - latDelta, lng + lngDelta, lat + latDelta]
}
