import { describe, expect, it } from 'vitest'
import {
  boundsOf,
  findSnapTarget,
  isSnapPrecisionUsable,
  isTargetInReach,
  type Project,
  type SnapCandidate,
  type SnapTarget,
} from './snapping'

/**
 * A simple equirectangular projection: 10000 pixels per degree, y downwards. Good enough
 * to reason about pixel distances, and unlike a real map it makes them predictable --
 * 0.0001 degrees is exactly one pixel.
 */
const project: Project = ([lng, lat]) => ({ x: lng * 10_000, y: -lat * 10_000 })

function candidate(geometry: GeoJSON.Geometry): SnapCandidate {
  return { geometry, bounds: boundsOf(geometry) }
}

/** A square with corners at whole hundredths, so a "close" pointer is easy to express. */
const SQUARE = candidate({
  type: 'Polygon',
  coordinates: [
    [
      [9.98, 53.54],
      [9.99, 53.54],
      [9.99, 53.55],
      [9.98, 53.55],
      [9.98, 53.54],
    ],
  ],
})

describe('findSnapTarget', () => {
  it('snaps to a nearby vertex', () => {
    // 3 pixels away from the corner at 9.98/53.54.
    const target = findSnapTarget([9.9803, 53.54], [SQUARE], project)

    expect(target?.kind).toBe('vertex')
    expect(target?.position).toEqual([9.98, 53.54])
  })

  it('returns the target coordinate exactly, not a rounded one', () => {
    // The test the plan calls for: a vertex snapped through pixel space and back would
    // land near the target instead of on it, and the resulting sliver between two
    // parcels is invisible for years.
    const precise = candidate({
      type: 'LineString',
      coordinates: [
        [9.9812345678901, 53.5412345678901],
        [9.99, 53.55],
      ],
    })

    const target = findSnapTarget([9.98124, 53.54124], [precise], project)

    expect(target?.position[0]).toBe(9.9812345678901)
    expect(target?.position[1]).toBe(53.5412345678901)
  })

  it('snaps to an edge when no vertex is close enough', () => {
    // Halfway along the bottom edge, a pixel above it -- far from either corner.
    const target = findSnapTarget([9.985, 53.5401], [SQUARE], project)

    expect(target?.kind).toBe('edge')
    expect(target?.position[0]).toBeCloseTo(9.985, 10)
    expect(target?.position[1]).toBeCloseTo(53.54, 10)
  })

  it('prefers a vertex even when an edge is nearer', () => {
    // Sitting almost exactly on the bottom edge, five pixels from the corner: the edge is
    // closer in pixels, but the corner is what anyone aiming here means.
    const target = findSnapTarget([9.9805, 53.54001], [SQUARE], project)

    expect(target?.kind).toBe('vertex')
    expect(target?.position).toEqual([9.98, 53.54])
  })

  it('picks the closest of several vertices', () => {
    const target = findSnapTarget([9.98995, 53.54], [SQUARE], project)

    expect(target?.position).toEqual([9.99, 53.54])
  })

  it('returns nothing beyond the tolerance', () => {
    // 50 pixels away from anything.
    expect(findSnapTarget([9.975, 53.535], [SQUARE], project)).toBeNull()
  })

  it('respects a custom tolerance', () => {
    // 6 pixels above the bottom edge and 10 from the nearest corner, so the two
    // tolerances below fall on either side of both.
    const pointer: [number, number] = [9.9808, 53.5406]

    expect(findSnapTarget(pointer, [SQUARE], project, 4)).toBeNull()
    expect(findSnapTarget(pointer, [SQUARE], project, 20)?.kind).toBe('vertex')
  })

  it('snaps to points and lines as well as polygons', () => {
    const point = candidate({ type: 'Point', coordinates: [9.98, 53.54] })
    const line = candidate({
      type: 'LineString',
      coordinates: [
        [10.0, 53.6],
        [10.01, 53.6],
      ],
    })

    expect(findSnapTarget([9.9801, 53.54], [point], project)?.position).toEqual([9.98, 53.54])
    expect(findSnapTarget([10.0001, 53.6], [line], project)?.position).toEqual([10.0, 53.6])
  })

  it('descends into multi geometries', () => {
    const multi = candidate({
      type: 'MultiPolygon',
      coordinates: [
        [[[9.0, 53.0], [9.01, 53.0], [9.01, 53.01], [9.0, 53.0]]],
        [[[12.0, 55.0], [12.01, 55.0], [12.01, 55.01], [12.0, 55.0]]],
      ],
    })

    expect(findSnapTarget([12.0001, 55.0], [multi], project)?.position).toEqual([12.0, 55.0])
  })

  it('ignores candidates whose bounds are far away', () => {
    const faraway = candidate({ type: 'Point', coordinates: [2.35, 48.85] })

    expect(findSnapTarget([9.98, 53.54], [faraway], project)).toBeNull()
  })

  it('handles an empty candidate list', () => {
    expect(findSnapTarget([9.98, 53.54], [], project)).toBeNull()
  })

  describe('intersections', () => {
    // A cross: the two lines meet at 9.985/53.545, a point neither of them has as a vertex.
    const horizontal = candidate({
      type: 'LineString',
      coordinates: [
        [9.98, 53.545],
        [9.99, 53.545],
      ],
    })
    const vertical = candidate({
      type: 'LineString',
      coordinates: [
        [9.985, 53.54],
        [9.985, 53.55],
      ],
    })

    it('snaps to where two lines cross', () => {
      const target = findSnapTarget([9.9851, 53.5451], [horizontal, vertical], project)

      expect(target?.kind).toBe('intersection')
      expect(target?.position[0]).toBeCloseTo(9.985, 12)
      expect(target?.position[1]).toBeCloseTo(53.545, 12)
    })

    it('prefers a crossing over a point on an edge', () => {
      // Both lines are within tolerance here, so an edge target exists as well -- but a
      // crossing is a place the data singles out, and an edge point is not.
      const target = findSnapTarget([9.9852, 53.5450], [horizontal, vertical], project)

      expect(target?.kind).toBe('intersection')
    })

    it('still prefers a vertex over a crossing', () => {
      // A line ending exactly on the crossing: the endpoint and the intersection sit on
      // the same coordinate, and the vertex is the more specific answer.
      const ending = candidate({
        type: 'LineString',
        coordinates: [
          [9.9855, 53.5455],
          [9.985, 53.545],
        ],
      })

      const target = findSnapTarget([9.98505, 53.54505], [horizontal, vertical, ending], project)

      expect(target?.kind).toBe('vertex')
    })

    it('does not treat the corner between two consecutive segments as a crossing', () => {
      // Neighbouring segments of one ring meet at a shared vertex. That is a vertex, and
      // reporting it as an intersection would only mislabel it.
      const corner = candidate({
        type: 'LineString',
        coordinates: [
          [9.98, 53.54],
          [9.985, 53.545],
          [9.99, 53.54],
        ],
      })

      const target = findSnapTarget([9.98501, 53.54501], [corner], project)

      expect(target?.kind).toBe('vertex')
    })

    it('ignores parallel lines', () => {
      const parallel = candidate({
        type: 'LineString',
        coordinates: [
          [9.98, 53.5455],
          [9.99, 53.5455],
        ],
      })

      // Right between the two parallels, 5 pixels from each: an edge, never a crossing.
      const target = findSnapTarget([9.985, 53.54525], [horizontal, parallel], project)

      expect(target?.kind).toBe('edge')
    })

    it('ignores segments that would only cross beyond their ends', () => {
      // Two short stubs whose infinite extensions meet, but the segments themselves do not.
      const stubA = candidate({
        type: 'LineString',
        coordinates: [
          [9.98, 53.545],
          [9.9805, 53.545],
        ],
      })
      const stubB = candidate({
        type: 'LineString',
        coordinates: [
          [9.985, 53.5445],
          [9.985, 53.5448],
        ],
      })

      const target = findSnapTarget([9.985, 53.545], [stubA, stubB], project)

      expect(target?.kind).not.toBe('intersection')
    })

    it('finds a crossing between two polygon edges', () => {
      const first = candidate({
        type: 'Polygon',
        coordinates: [[[9.98, 53.54], [9.99, 53.54], [9.99, 53.55], [9.98, 53.55], [9.98, 53.54]]],
      })
      const second = candidate({
        type: 'Polygon',
        coordinates: [[[9.985, 53.535], [9.995, 53.535], [9.995, 53.545], [9.985, 53.545], [9.985, 53.535]]],
      })

      // The right edge of the first square crosses the top edge of the second at 9.99/53.545.
      const target = findSnapTarget([9.9901, 53.5451], [first, second], project)

      expect(target?.kind).toBe('intersection')
      expect(target?.position[0]).toBeCloseTo(9.99, 12)
      expect(target?.position[1]).toBeCloseTo(53.545, 12)
    })
  })
})

describe('precision of computed positions', () => {
  /** Decimal places of a number, the way the drawing tool counts them. */
  function places(value: number): number {
    const text = String(value)
    const dot = text.indexOf('.')
    return dot < 0 ? 0 : text.length - dot - 1
  }

  it('keeps a snapped edge position within nine decimal places', () => {
    // Interpolating along an edge yields the full precision of a double -- around fifteen
    // places. terra-draw rejects such a feature outright ("coordinates with excessive
    // precision"), which surfaced as undo being unable to restore a drawn shape.
    const line = candidate({
      type: 'LineString',
      coordinates: [
        [9.981234567, 53.541234567],
        [9.987654321, 53.547654321],
      ],
    })

    const target = findSnapTarget([9.9845, 53.5443], [line], project)

    expect(target?.kind).toBe('edge')
    expect(places(target!.position[0])).toBeLessThanOrEqual(9)
    expect(places(target!.position[1])).toBeLessThanOrEqual(9)
  })

  it('keeps a crossing within nine decimal places', () => {
    const a = candidate({
      type: 'LineString',
      coordinates: [
        [9.981111111, 53.541111111],
        [9.989999999, 53.549999999],
      ],
    })
    const b = candidate({
      type: 'LineString',
      coordinates: [
        [9.981111111, 53.549999999],
        [9.989999999, 53.541111111],
      ],
    })

    const target = findSnapTarget([9.9855, 53.54555], [a, b], project)

    expect(target?.kind).toBe('intersection')
    expect(places(target!.position[0])).toBeLessThanOrEqual(9)
    expect(places(target!.position[1])).toBeLessThanOrEqual(9)
  })

  it('still returns a vertex bit for bit', () => {
    // The rounding must never reach a vertex: that coordinate is what the data states,
    // and shaving it is the silent gap between neighbouring parcels (plan section D.1).
    const precise = candidate({
      type: 'Point',
      coordinates: [9.9812345678901, 53.5412345678901],
    })

    const target = findSnapTarget([9.98123456, 53.54123456], [precise], project)

    expect(target?.kind).toBe('vertex')
    expect(target?.position[0]).toBe(9.9812345678901)
    expect(target?.position[1]).toBe(53.5412345678901)
  })
})

describe('isTargetInReach', () => {
  const target: SnapTarget = {
    position: [9.98, 53.54],
    kind: 'vertex',
    // Deliberately nonsense: the distance recorded when the target was found says nothing
    // about where the pointer is now, and this guards against the check reading it.
    distancePx: 0,
  }

  it('accepts a target the pointer is still near', () => {
    // 3 pixels away.
    expect(isTargetInReach(target, [9.9803, 53.54], project)).toBe(true)
  })

  it('rejects a target the pointer has moved away from', () => {
    // 50 pixels away -- a preview left over from before a pan.
    expect(isTargetInReach(target, [9.985, 53.54], project)).toBe(false)
  })

  it('measures against the given tolerance', () => {
    // 15 pixels away: beyond the default of 12, inside a widened 20.
    const pointer: [number, number] = [9.9815, 53.54]

    expect(isTargetInReach(target, pointer, project)).toBe(false)
    expect(isTargetInReach(target, pointer, project, 20)).toBe(true)
  })
})

describe('mapUnitsPerPixel (via findSnapTarget under an anisotropic projection)', () => {
  it('does not lose a candidate whose bounding-box distance is large only in the coarse direction', () => {
    // A projection standing in for a pitched view: longitude reads at high resolution
    // (10000 px/degree), the way the near edge of a tilted screen still does; latitude
    // reads at a hundredth of that, the way the far edge does approaching the horizon.
    // Before `mapUnitsPerPixel` took the worse of the two directions, the cheap bounds
    // pre-filter converted the 12px tolerance using longitude's resolution alone,
    // undersizing the latitude margin about a hundredfold -- rejecting this vertex on
    // its bounding box before its real, pixel-space distance (0.5px) was ever measured.
    const anisotropic: Project = ([lng, lat]) => ({ x: (lng - 10) * 10_000, y: -(lat - 53.55) * 100 })
    const vertex = candidate({ type: 'Point', coordinates: [10, 53.555] })

    const target = findSnapTarget([10, 53.55], [vertex], anisotropic)

    expect(target?.kind).toBe('vertex')
    expect(target?.position).toEqual([10, 53.555])
  })
})

describe('isSnapPrecisionUsable', () => {
  it('stays usable on a flat, unpitched projection', () => {
    expect(isSnapPrecisionUsable([9.985, 53.545], [9.98, 53.54], project)).toBe(true)
  })

  it('refuses a pointer where the ground resolution is far coarser than at the map centre', () => {
    // Longitude stays sharp everywhere (10000 px/degree); latitude only degrades north
    // of 53.6, standing in for the sliver of screen near the horizon under pitch.
    const pitchedNearHorizon: Project = ([lng, lat]) => {
      const latScale = lat > 53.6 ? 100 : 10_000
      return { x: lng * 10_000, y: -lat * latScale }
    }
    const center: [number, number] = [9.985, 53.55]

    expect(isSnapPrecisionUsable(center, center, pitchedNearHorizon)).toBe(true)
    expect(isSnapPrecisionUsable([9.985, 53.65], center, pitchedNearHorizon)).toBe(false)
  })

  it('does not block anything on a degenerate transform', () => {
    const degenerate: Project = () => ({ x: 0, y: 0 })
    expect(isSnapPrecisionUsable([9.985, 53.545], [9.98, 53.54], degenerate)).toBe(true)
  })
})

describe('boundsOf', () => {
  it('covers every part of a multi geometry', () => {
    expect(
      boundsOf({
        type: 'MultiPoint',
        coordinates: [
          [9.0, 53.0],
          [12.0, 55.0],
        ],
      }),
    ).toEqual([9.0, 53.0, 12.0, 55.0])
  })

  it('gives a point a zero-size box', () => {
    expect(boundsOf({ type: 'Point', coordinates: [9.98, 53.54] })).toEqual([
      9.98, 53.54, 9.98, 53.54,
    ])
  })
})
