import { describe, expect, it } from 'vitest'
import { toSinglePart } from './singlePart'

describe('toSinglePart', () => {
  it('passes single geometries through untouched', () => {
    const point: GeoJSON.Geometry = { type: 'Point', coordinates: [9.98, 53.54] }
    expect(toSinglePart(point)).toBe(point)
  })

  it('unwraps a multi geometry with exactly one part', () => {
    // What the import produces for every ordinary shapefile row.
    expect(
      toSinglePart({ type: 'MultiPolygon', coordinates: [[[[9, 53], [10, 53], [10, 54], [9, 53]]]] }),
    ).toEqual({ type: 'Polygon', coordinates: [[[9, 53], [10, 53], [10, 54], [9, 53]]] })

    expect(toSinglePart({ type: 'MultiPoint', coordinates: [[9, 53]] })).toEqual({
      type: 'Point',
      coordinates: [9, 53],
    })

    expect(
      toSinglePart({ type: 'MultiLineString', coordinates: [[[9, 53], [10, 54]]] }),
    ).toEqual({ type: 'LineString', coordinates: [[9, 53], [10, 54]] })
  })

  it('refuses a genuinely multi-part geometry instead of keeping the first part', () => {
    // Editing a building and silently saving away its second part is the kind of loss
    // that surfaces years later.
    expect(
      toSinglePart({
        type: 'MultiPolygon',
        coordinates: [
          [[[9, 53], [10, 53], [10, 54], [9, 53]]],
          [[[11, 55], [12, 55], [12, 56], [11, 55]]],
        ],
      }),
    ).toBeNull()
  })

  it('refuses an empty multi geometry', () => {
    expect(toSinglePart({ type: 'MultiPolygon', coordinates: [] })).toBeNull()
  })

  it('refuses a geometry collection', () => {
    expect(
      toSinglePart({ type: 'GeometryCollection', geometries: [] } as GeoJSON.Geometry),
    ).toBeNull()
  })
})
