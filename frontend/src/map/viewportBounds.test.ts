import { describe, expect, it } from 'vitest'
import { geodesicDistance, type LngLat } from '@/measurement/geodesy'
import { metersPerPixel } from './scale'
import { isPitchExpanded, type Bbox } from './viewportBounds'

const WIDTH = 1000
const HEIGHT = 800
const CENTER: LngLat = [10, 53.55]
const ZOOM = 15

/** The bbox a flat (pitch zero) view of this size would report at `ZOOM`. */
function flatBbox(): Bbox {
  const mpp = metersPerPixel(CENTER[1], ZOOM)
  const latPerMeter = 1 / 111_320
  const lngPerMeter = 1 / (111_320 * Math.cos((CENTER[1] * Math.PI) / 180))
  const dLat = ((HEIGHT / 2) * mpp) * latPerMeter
  const dLng = ((WIDTH / 2) * mpp) * lngPerMeter
  return [CENTER[0] - dLng, CENTER[1] - dLat, CENTER[0] + dLng, CENTER[1] + dLat]
}

/**
 * A bbox whose diagonal is exactly `ratio` times a flat view's, built by moving the
 * north edge only -- the same edge a real pitched `getBounds()` actually stretches.
 * Found by bisection rather than solved algebraically: it only has to reproduce
 * `isPitchExpanded`'s own ratio exactly, not a closed-form guess at one.
 */
function bboxAtRatio(ratio: number): Bbox {
  const [west, south, east] = flatBbox()
  const targetDiagonal = metersPerPixel(CENTER[1], ZOOM) * Math.hypot(WIDTH, HEIGHT) * ratio

  let tooClose = CENTER[1]
  let farEnough = CENTER[1] + 50 // 50 degrees north is far past any ratio these tests use.
  for (let i = 0; i < 40; i += 1) {
    const mid = (tooClose + farEnough) / 2
    const diagonal = geodesicDistance([west, south], [east, mid])
    if (diagonal > targetDiagonal) farEnough = mid
    else tooClose = mid
  }
  return [west, south, east, farEnough]
}

describe('isPitchExpanded', () => {
  it('is false for a flat view -- the ordinary case, not just a boundary', () => {
    expect(
      isPitchExpanded({ bbox: flatBbox(), width: WIDTH, height: HEIGHT, center: CENTER, zoom: ZOOM, wasExpanded: false }),
    ).toBe(false)
  })

  it('is false for an ordinary looking-around tilt, on either side of the hysteresis', () => {
    // A stand-in for ~30 degrees of pitch: the measured diagonal ratio stayed under 1.23
    // in the browser at every zoom tested, well under even the lower threshold.
    const bbox = bboxAtRatio(1.2)
    expect(isPitchExpanded({ bbox, width: WIDTH, height: HEIGHT, center: CENTER, zoom: ZOOM, wasExpanded: false })).toBe(false)
    expect(isPitchExpanded({ bbox, width: WIDTH, height: HEIGHT, center: CENTER, zoom: ZOOM, wasExpanded: true })).toBe(false)
  })

  it('is true once the bbox reaches well past what the zoom level would show flat', () => {
    // A stand-in for pitch at or beyond the 60 degree ceiling: measured diagonal ratios
    // of 2.2 and above in the browser.
    const bbox = bboxAtRatio(2.3)
    expect(isPitchExpanded({ bbox, width: WIDTH, height: HEIGHT, center: CENTER, zoom: ZOOM, wasExpanded: false })).toBe(true)
  })

  it('does not throw or misfire on a degenerate transform, regardless of prior state', () => {
    const degenerate = { bbox: [10, 53.55, 10, 53.55] as Bbox, width: 0, height: 0, center: CENTER, zoom: ZOOM }
    expect(isPitchExpanded({ ...degenerate, wasExpanded: false })).toBe(false)
    expect(isPitchExpanded({ ...degenerate, wasExpanded: true })).toBe(false)
  })

  it('scales with zoom rather than firing on an ordinary zoomed-out flat view', () => {
    // A wide, unpitched view at a low zoom has a large bbox too -- the point of
    // comparing against a flat reference at the *same* zoom is that this alone must
    // never count as "expanded".
    const wideZoom = 8
    const mpp = metersPerPixel(CENTER[1], wideZoom)
    const latPerMeter = 1 / 111_320
    const lngPerMeter = 1 / (111_320 * Math.cos((CENTER[1] * Math.PI) / 180))
    const dLat = ((HEIGHT / 2) * mpp) * latPerMeter
    const dLng = ((WIDTH / 2) * mpp) * lngPerMeter
    const bbox: Bbox = [CENTER[0] - dLng, CENTER[1] - dLat, CENTER[0] + dLng, CENTER[1] + dLat]

    expect(
      isPitchExpanded({ bbox, width: WIDTH, height: HEIGHT, center: CENTER, zoom: wideZoom, wasExpanded: false }),
    ).toBe(false)
  })

  describe('hysteresis', () => {
    function check(ratio: number, wasExpanded: boolean): boolean {
      return isPitchExpanded({ bbox: bboxAtRatio(ratio), width: WIDTH, height: HEIGHT, center: CENTER, zoom: ZOOM, wasExpanded })
    }

    it('does not turn on at a ratio inside the band that a single threshold would already fire at', () => {
      // 1.5 is where the old single threshold sat -- inside the 1.4-1.6 band, the note
      // must stay off until the ratio actually clears the top of it.
      expect(check(1.5, false)).toBe(false)
    })

    it('does not turn off at a ratio inside the band either, once already on', () => {
      expect(check(1.5, true)).toBe(true)
    })

    it('turns on only once the ratio clears the high threshold', () => {
      expect(check(1.59, false)).toBe(false)
      expect(check(1.61, false)).toBe(true)
    })

    it('turns off only once the ratio falls below the low threshold', () => {
      expect(check(1.41, true)).toBe(true)
      expect(check(1.39, true)).toBe(false)
    })

    it('does not toggle while hovering inside the band -- the tremor a single threshold could not survive', () => {
      // A stand-in for a hand trembling near 45 degrees of pitch: several steps that
      // would cross a single 1.5 threshold back and forth, none of them leaving 1.4-1.6.
      const wobble = [1.48, 1.52, 1.47, 1.53, 1.49, 1.51]
      let expanded = false
      let toggles = 0
      for (const ratio of wobble) {
        const next = check(ratio, expanded)
        if (next !== expanded) toggles += 1
        expanded = next
      }
      expect(toggles).toBe(0)
    })

    it('turns on at a higher ratio than it turns off at -- the asymmetry a shared threshold cannot have', () => {
      // Rising past 1.6 switches it on; falling back only switches it off once past 1.4
      // -- two different ratios, not the one 1.5 a single threshold would use both ways.
      const risingThenFalling = [1.3, 1.45, 1.58, 1.65, 1.7, 1.55, 1.42, 1.38, 1.3]
      let expanded = false
      const turnedOnAt: number[] = []
      const turnedOffAt: number[] = []
      for (const ratio of risingThenFalling) {
        const next = check(ratio, expanded)
        if (next && !expanded) turnedOnAt.push(ratio)
        if (!next && expanded) turnedOffAt.push(ratio)
        expanded = next
      }
      expect(turnedOnAt).toEqual([1.65])
      expect(turnedOffAt).toEqual([1.38])
      expect(turnedOnAt[0]).toBeGreaterThan(turnedOffAt[0])
    })
  })
})
