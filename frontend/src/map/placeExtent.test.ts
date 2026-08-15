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

  // The point of the address radius: a house number is one building, and the framing a
  // street gets would leave it a few pixels wide. Compared against the street box rather
  // than against a fixed number, so the two stay in the intended order even if either
  // radius is retuned -- what must not silently flip is which one sits closer.
  test('frames an address more tightly than a street at the same point', () => {
    const point: [number, number] = [10.0936, 53.5769]
    const street = placeExtent(...point, 'street')
    const address = placeExtent(...point, 'address')

    const streetWidth = geodesicDistance([street[0], point[1]], [street[2], point[1]])
    const addressWidth = geodesicDistance([address[0], point[1]], [address[2], point[1]])
    const streetHeight = geodesicDistance([point[0], street[1]], [point[0], street[3]])
    const addressHeight = geodesicDistance([point[0], address[1]], [point[0], address[3]])

    expect(addressWidth).toBeLessThan(streetWidth)
    expect(addressHeight).toBeLessThan(streetHeight)
    // 200 m across, the frame the constant is documented for -- a box that merely came
    // out smaller than the street's would also pass the two comparisons above.
    expect(addressWidth).toBeGreaterThan(195)
    expect(addressWidth).toBeLessThan(205)
    expect(addressHeight).toBeGreaterThan(195)
    expect(addressHeight).toBeLessThan(205)
  })

  // Every other kind keeps the neighbourhood framing, and so does a call that passes no
  // kind at all -- only `address` is special-cased.
  test('leaves every other kind on the neighbourhood radius', () => {
    const point: [number, number] = [10.0, 53.55]
    const neighbourhood = placeExtent(...point)
    for (const kind of ['street', 'district', 'place'] as const) {
      expect(placeExtent(...point, kind)).toEqual(neighbourhood)
    }
  })

  // The cos(latitude) correction has to apply to the address radius too -- a second
  // radius that skipped it would draw a box 70% too narrow east-west in Hamburg, and the
  // width assertions above alone would not notice at a single latitude.
  test('applies the meridian convergence to the address radius as well', () => {
    const atEquator = placeExtent(0, 0, 'address')
    const nearPole = placeExtent(0, 80, 'address')
    expect(nearPole[2] - nearPole[0]).toBeGreaterThan(atEquator[2] - atEquator[0])
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
