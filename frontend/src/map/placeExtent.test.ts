import { describe, expect, test } from 'vitest'
import { geodesicDistance } from '@/measurement/geodesy'
import { placeExtent } from './placeExtent'

describe('placeExtent', () => {
  test('centres the point in the box', () => {
    const [minLng, minLat, maxLng, maxLat] = placeExtent(10.0, 53.55)
    const centerLng = (minLng + maxLng) / 2
    const centerLat = (minLat + maxLat) / 2
    expect(centerLng).toBeCloseTo(10.0, 9)
    expect(centerLat).toBeCloseTo(53.55, 9)
  })

  // Checked against the same ellipsoidal formula the measuring tool uses, independent of
  // whatever meridian-convergence factor placeExtent applies internally -- a wrong factor
  // would still produce a symmetric box, so the centring test above cannot catch it.
  test('each edge sits roughly 300 m from the point, at a Hamburg latitude', () => {
    const point: [number, number] = [10.0, 53.55]
    const [minLng, minLat, maxLng, maxLat] = placeExtent(...point)

    const north = geodesicDistance(point, [point[0], maxLat])
    const south = geodesicDistance(point, [point[0], minLat])
    const east = geodesicDistance(point, [maxLng, point[1]])
    const west = geodesicDistance(point, [minLng, point[1]])

    for (const distance of [north, south, east, west]) {
      expect(distance).toBeGreaterThan(295)
      expect(distance).toBeLessThan(305)
    }
  })

  test('narrows the longitude delta near the pole, widens it at the equator', () => {
    const atEquator = placeExtent(0, 0)
    const nearPole = placeExtent(0, 80)
    const equatorLngWidth = atEquator[2] - atEquator[0]
    const poleLngWidth = nearPole[2] - nearPole[0]
    expect(poleLngWidth).toBeGreaterThan(equatorLngWidth)
  })

  test('the latitude delta stays constant regardless of latitude', () => {
    const atEquator = placeExtent(0, 0)
    const nearPole = placeExtent(0, 80)
    const equatorLatHeight = atEquator[3] - atEquator[1]
    const poleLatHeight = nearPole[3] - nearPole[1]
    expect(poleLatHeight).toBeCloseTo(equatorLatHeight, 9)
  })
})
