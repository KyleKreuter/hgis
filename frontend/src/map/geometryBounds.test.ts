import { describe, expect, it } from 'vitest'
import { boundsOfGeometry } from './geometryBounds'

describe('boundsOfGeometry', () => {
  it('takes a point as its own zero-size box', () => {
    // fitBounds answers a zero-size box with maximum zoom, which is why ZoomToExtent
    // caps it -- the box itself is correct.
    expect(boundsOfGeometry({ type: 'Point', coordinates: [9.98, 53.54] })).toEqual([
      9.98, 53.54, 9.98, 53.54,
    ])
  })

  it('spans a linestring', () => {
    expect(
      boundsOfGeometry({
        type: 'LineString',
        coordinates: [
          [9.9, 53.5],
          [10.1, 53.6],
          [10.0, 53.4],
        ],
      }),
    ).toEqual([9.9, 53.4, 10.1, 53.6])
  })

  it('descends through a polygon with a hole', () => {
    expect(
      boundsOfGeometry({
        type: 'Polygon',
        coordinates: [
          [
            [9, 53],
            [11, 53],
            [11, 54],
            [9, 54],
            [9, 53],
          ],
          [
            [9.5, 53.5],
            [10.5, 53.5],
            [10.5, 53.8],
            [9.5, 53.5],
          ],
        ],
      }),
    ).toEqual([9, 53, 11, 54])
  })

  it('covers every part of a multipolygon', () => {
    expect(
      boundsOfGeometry({
        type: 'MultiPolygon',
        coordinates: [
          [[[9, 53], [9.5, 53], [9.5, 53.5], [9, 53]]],
          [[[12, 55], [12.5, 55], [12.5, 55.5], [12, 55]]],
        ],
      }),
    ).toEqual([9, 53, 12.5, 55.5])
  })

  it('handles negative and crossing-zero coordinates', () => {
    expect(
      boundsOfGeometry({
        type: 'LineString',
        coordinates: [
          [-1.5, -0.5],
          [2.5, 1.5],
        ],
      }),
    ).toEqual([-1.5, -0.5, 2.5, 1.5])
  })

  it('returns null rather than a box of infinities', () => {
    // A caller cannot zoom to nothing; a box built from Infinity would silently send the
    // map somewhere entirely else.
    expect(boundsOfGeometry(null)).toBeNull()
    expect(boundsOfGeometry(undefined)).toBeNull()
    expect(boundsOfGeometry({ type: 'MultiPolygon', coordinates: [] })).toBeNull()
  })
})
