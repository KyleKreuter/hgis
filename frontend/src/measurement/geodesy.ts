/**
 * Distance and area on the WGS84 ellipsoid, for coordinates as MapLibre hands them
 * out: longitude/latitude in degrees (EPSG:4326).
 *
 * Written out here rather than pulled in as a dependency (turf is the usual answer)
 * because two functions of thirty lines each do not justify a package, and because
 * the two things a measuring tool must not get wrong -- the earth being neither flat
 * nor a sphere -- are exactly what those thirty lines are about.
 */

/** Longitude first, as everywhere else in GeoJSON and MapLibre. */
export type LngLat = [number, number]

const SEMI_MAJOR = 6_378_137
const FLATTENING = 1 / 298.257223563
const SEMI_MINOR = SEMI_MAJOR * (1 - FLATTENING)
const ECCENTRICITY_SQ = FLATTENING * (2 - FLATTENING)
const ECCENTRICITY = Math.sqrt(ECCENTRICITY_SQ)

/** Mean radius (IUGG), used only where Vincenty gives up -- see `geodesicDistance`. */
const MEAN_RADIUS = 6_371_008.8

function toRadians(degrees: number): number {
  return (degrees * Math.PI) / 180
}

/**
 * Folds a longitude difference into [-180, 180).
 *
 * MapLibre keeps counting past 180 when the map is panned around the globe, so two
 * clicks either side of the antimeridian can arrive 359 degrees apart when they are
 * one degree from each other. Every formula below reads differences, never absolute
 * longitudes, so folding here is all that antimeridian handling takes.
 */
function wrapDegrees(delta: number): number {
  return ((((delta + 180) % 360) + 360) % 360) - 180
}

/**
 * Vincenty's inverse formula: the distance along the shortest path on the ellipsoid,
 * accurate to well under a millimetre.
 *
 * Returns null instead of a wrong number when the iteration does not settle, which
 * happens for near-antipodal pairs -- a known limit of the method, not a bug.
 */
function vincentyInverse(from: LngLat, to: LngLat): number | null {
  const deltaLon = toRadians(wrapDegrees(to[0] - from[0]))
  // Reduced latitudes: the ellipsoid problem restated on the auxiliary sphere.
  const u1 = Math.atan((1 - FLATTENING) * Math.tan(toRadians(from[1])))
  const u2 = Math.atan((1 - FLATTENING) * Math.tan(toRadians(to[1])))
  const sinU1 = Math.sin(u1)
  const cosU1 = Math.cos(u1)
  const sinU2 = Math.sin(u2)
  const cosU2 = Math.cos(u2)

  let lambda = deltaLon

  for (let iteration = 0; iteration < 100; iteration += 1) {
    const sinLambda = Math.sin(lambda)
    const cosLambda = Math.cos(lambda)
    const sinSigma = Math.hypot(
      cosU2 * sinLambda,
      cosU1 * sinU2 - sinU1 * cosU2 * cosLambda,
    )
    // Coincident points: the same click twice, or a vertex dropped on its predecessor.
    if (sinSigma === 0) return 0

    const cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
    const sigma = Math.atan2(sinSigma, cosSigma)
    const sinAlpha = (cosU1 * cosU2 * sinLambda) / sinSigma
    const cosSqAlpha = 1 - sinAlpha * sinAlpha
    // Zero along the equator, where the midpoint term is undefined and drops out.
    const cos2SigmaM = cosSqAlpha === 0 ? 0 : cosSigma - (2 * sinU1 * sinU2) / cosSqAlpha
    const c = (FLATTENING / 16) * cosSqAlpha * (4 + FLATTENING * (4 - 3 * cosSqAlpha))
    const previous = lambda

    lambda =
      deltaLon +
      (1 - c) *
        FLATTENING *
        sinAlpha *
        (sigma +
          c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))

    if (Math.abs(lambda - previous) < 1e-12) {
      const uSq =
        (cosSqAlpha * (SEMI_MAJOR * SEMI_MAJOR - SEMI_MINOR * SEMI_MINOR)) /
        (SEMI_MINOR * SEMI_MINOR)
      const a = 1 + (uSq / 16384) * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
      const b = (uSq / 1024) * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
      const deltaSigma =
        b *
        sinSigma *
        (cos2SigmaM +
          (b / 4) *
            (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
              (b / 6) *
                cos2SigmaM *
                (-3 + 4 * sinSigma * sinSigma) *
                (-3 + 4 * cos2SigmaM * cos2SigmaM)))

      return SEMI_MINOR * a * (sigma - deltaSigma)
    }
  }

  return null
}

/** Great-circle distance on a sphere of mean radius. Off by up to 0.5%, hence the fallback role. */
function haversine(from: LngLat, to: LngLat): number {
  const lat1 = toRadians(from[1])
  const lat2 = toRadians(to[1])
  const deltaLat = lat2 - lat1
  const deltaLon = toRadians(wrapDegrees(to[0] - from[0]))
  const h =
    Math.sin(deltaLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) ** 2
  return 2 * MEAN_RADIUS * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** Distance between two coordinates in metres. */
export function geodesicDistance(from: LngLat, to: LngLat): number {
  return vincentyInverse(from, to) ?? haversine(from, to)
}

/** Summed length of a polyline in metres; 0 for fewer than two points. */
export function pathLength(path: readonly LngLat[]): number {
  let total = 0
  for (let i = 1; i < path.length; i += 1) {
    total += geodesicDistance(path[i - 1], path[i])
  }
  return total
}

/**
 * Authalic latitude: the latitude a point would have on the sphere that carries the
 * same total surface as the ellipsoid, chosen so that every band between two parallels
 * keeps its area. It is what turns the spherical area formula below into an ellipsoidal
 * one -- without it the result is off by roughly half a percent, which on a hectare is
 * fifty square metres and on a building plot is an argument.
 */
function authalicQ(sinLat: number): number {
  return (
    (1 - ECCENTRICITY_SQ) *
    (sinLat / (1 - ECCENTRICITY_SQ * sinLat * sinLat) -
      (1 / (2 * ECCENTRICITY)) *
        Math.log((1 - ECCENTRICITY * sinLat) / (1 + ECCENTRICITY * sinLat)))
  )
}

const Q_POLE = authalicQ(1)
const AUTHALIC_RADIUS = SEMI_MAJOR * Math.sqrt(Q_POLE / 2)

function authalicSinLatitude(latitude: number): number {
  const ratio = authalicQ(Math.sin(toRadians(latitude))) / Q_POLE
  // Clamped against the float error that a pole (|ratio| = 1) would otherwise turn into NaN.
  return Math.min(1, Math.max(-1, ratio))
}

/**
 * True when the ring runs all the way around the earth in longitude, which is what a
 * ring enclosing a pole does and what no other ring can do.
 *
 * Each edge contributes its folded longitude difference; for an ordinary ring these
 * cancel to zero, for one around a pole they add up to a full turn. Half a turn is
 * used as the threshold because nothing in between can occur.
 */
function enclosesPole(points: readonly LngLat[]): boolean {
  let turned = 0
  for (let i = 0; i < points.length; i += 1) {
    turned += wrapDegrees(points[(i + 1) % points.length][0] - points[i][0])
  }
  return Math.abs(turned) > 180
}

/**
 * Area of a ring in square metres, always positive -- the sign only encodes winding
 * order, which a measurement has no use for.
 *
 * The ring may be given open or closed; a repeated last point is dropped. Rings with
 * fewer than three distinct points enclose nothing and measure 0.
 *
 * Method: the line integral over sin(latitude) d(longitude), the same one Chamberlain
 * and Duquette describe and every GIS uses, but evaluated in authalic latitude so it
 * holds on the ellipsoid rather than on a sphere.
 *
 * A ring around a pole is the one case the integral does not answer on its own: it
 * measures the region between the ring and the equator and misses the cap above it,
 * so a circle drawn around the North Pole came out as roughly a quarter of the planet
 * instead of the few thousand square kilometres it encloses. There the complement is
 * what was drawn -- the whole hemisphere's worth of integral minus the part the ring
 * cuts off -- which is the correction below.
 *
 * A closed ring always divides the earth into two parts, and around a pole the winding
 * order is the only thing that says which one is meant. Since the sign is discarded
 * here by design, the smaller of the two is reported -- the pole cap, which is what
 * someone drawing a ring around a pole is asking about.
 */
export function geodesicArea(ring: readonly LngLat[]): number {
  const points = closeIsImplied(ring)
  if (points.length < 3) return 0

  let total = 0
  for (let i = 0; i < points.length; i += 1) {
    const previous = points[(i - 1 + points.length) % points.length]
    const next = points[(i + 1) % points.length]
    total += toRadians(wrapDegrees(next[0] - previous[0])) * authalicSinLatitude(points[i][1])
  }

  const integral = Math.abs(total / 2)
  const excess = enclosesPole(points)
    ? 2 * Math.PI - Math.min(integral, 2 * Math.PI)
    : integral

  return excess * AUTHALIC_RADIUS * AUTHALIC_RADIUS
}

/** Strips the repeated closing vertex, so open and closed rings measure the same. */
function closeIsImplied(ring: readonly LngLat[]): readonly LngLat[] {
  const first = ring[0]
  const last = ring[ring.length - 1]
  if (ring.length > 1 && first[0] === last[0] && first[1] === last[1]) {
    return ring.slice(0, -1)
  }
  return ring
}
