import { describe, expect, it } from 'vitest'
import { boundsOf, findSnapTarget, type Project, type SnapCandidate } from './snapping'

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
