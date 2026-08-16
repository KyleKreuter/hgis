import { describe, expect, it } from 'vitest'
import { geodesicDistance, type LngLat } from '@/measurement/geodesy'
import { viewportQueryBounds } from './viewportBounds'

const WIDTH = 1000
const HEIGHT = 800
const CENTER: LngLat = [10, 53.55]

/**
 * A screen where longitude is linear in `x` and latitude in `y` splits into two
 * unrelated halves: below the centre row (`y > height/2`, the near/camera edge) it moves
 * mildly and linearly, the way an unpitched map always does; above it (`y < height/2`,
 * the far/horizon edge) it accelerates the closer `y` gets to 0, the way a pitched
 * map's far edge does as it approaches the horizon. `flat: true` makes the top behave
 * exactly like the bottom, standing in for pitch zero.
 */
function makeUnproject({ flat }: { flat: boolean }) {
  return ([x, y]: [number, number]): LngLat => {
    const lng = CENTER[0] + ((x - WIDTH / 2) / WIDTH) * 0.01
    const half = HEIGHT / 2
    if (y >= half) {
      const t = (y - half) / half
      return [lng, CENTER[1] - 0.0015 * t]
    }
    const t = (half - y) / half
    const latOffset = flat ? 0.0015 * t : (0.001 * t) / (1.0001 - t)
    return [lng, CENTER[1] + latOffset]
  }
}

describe('viewportQueryBounds', () => {
  it('matches a plain four-corner unprojection at pitch zero', () => {
    const unproject = makeUnproject({ flat: true })
    const bbox = viewportQueryBounds({ width: WIDTH, height: HEIGHT, center: CENTER, unproject })

    const [topLeft] = [unproject([0, 0])]
    const bottomRight = unproject([WIDTH, HEIGHT])
    expect(bbox[0]).toBeCloseTo(topLeft[0], 6)
    expect(bbox[2]).toBeCloseTo(bottomRight[0], 6)
    expect(bbox[3]).toBeCloseTo(topLeft[1], 6)
    expect(bbox[1]).toBeCloseTo(bottomRight[1], 6)
  })

  it('pulls the far edge in rather than following it to the horizon', () => {
    const unproject = makeUnproject({ flat: false })
    const naiveNorth = unproject([WIDTH / 2, 0])[1]
    const bbox = viewportQueryBounds({ width: WIDTH, height: HEIGHT, center: CENTER, unproject })

    // The mock's far edge would otherwise reach almost ten degrees north -- a stand-in
    // for `getBounds()` reaching from Hamburg toward Stockholm on a 60 degree tilt.
    expect(naiveNorth - CENTER[1]).toBeGreaterThan(1)
    expect(bbox[3] - CENTER[1]).toBeLessThan(0.01)
  })

  it('never reaches farther than the near edge already does', () => {
    const unproject = makeUnproject({ flat: false })
    const bbox = viewportQueryBounds({ width: WIDTH, height: HEIGHT, center: CENTER, unproject })
    const nearDistance = geodesicDistance(CENTER, unproject([WIDTH / 2, HEIGHT]))
    const farDistance = geodesicDistance(CENTER, [CENTER[0], bbox[3]])

    expect(farDistance).toBeLessThanOrEqual(nearDistance * 1.001)
  })

  it('leaves the near edge and the sides untouched -- only the far edge moves', () => {
    const unproject = makeUnproject({ flat: false })
    const bbox = viewportQueryBounds({ width: WIDTH, height: HEIGHT, center: CENTER, unproject })

    const bottomLeft = unproject([0, HEIGHT])
    const bottomRight = unproject([WIDTH, HEIGHT])
    expect(bbox[1]).toBe(bottomLeft[1])
    expect(bbox[0]).toBe(bottomLeft[0])
    expect(bbox[2]).toBe(bottomRight[0])
  })

  it('falls back to the untouched top edge when the near edge is degenerate', () => {
    // A mocked transform that reports the same point everywhere -- nothing to measure a
    // "near edge distance" against. Must not loop forever or divide by zero.
    const unproject = () => CENTER
    const bbox = viewportQueryBounds({ width: WIDTH, height: HEIGHT, center: CENTER, unproject })

    expect(bbox).toEqual([CENTER[0], CENTER[1], CENTER[0], CENTER[1]])
  })
})
