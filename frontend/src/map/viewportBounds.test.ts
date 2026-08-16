import { describe, expect, it } from 'vitest'
import type { LngLat } from '@/measurement/geodesy'
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

describe('isPitchExpanded', () => {
  it('is false for a flat view -- the ordinary case, not just a boundary', () => {
    expect(isPitchExpanded({ bbox: flatBbox(), width: WIDTH, height: HEIGHT, center: CENTER, zoom: ZOOM })).toBe(false)
  })

  it('is false for an ordinary looking-around tilt', () => {
    // A stand-in for ~30 degrees of pitch: the measured diagonal ratio at 30 degrees
    // stayed under 1.3 in the browser regardless of zoom.
    const [w, s, e, n] = flatBbox()
    const midLat = (s + n) / 2
    const mildlyWider: Bbox = [w - (e - w) * 0.1, s, e + (e - w) * 0.1, midLat + (n - midLat) * 1.2]
    expect(isPitchExpanded({ bbox: mildlyWider, width: WIDTH, height: HEIGHT, center: CENTER, zoom: ZOOM })).toBe(false)
  })

  it('is true once the bbox reaches well past what the zoom level would show flat', () => {
    // A stand-in for pitch at or beyond the 60 degree ceiling: measured diagonal ratios
    // of 2.5 and above in the browser.
    const [w, s, e, n] = flatBbox()
    const farNorth = n + (n - s) * 2
    const stretched: Bbox = [w, s, e, farNorth]
    expect(isPitchExpanded({ bbox: stretched, width: WIDTH, height: HEIGHT, center: CENTER, zoom: ZOOM })).toBe(true)
  })

  it('does not throw or misfire on a degenerate transform', () => {
    expect(
      isPitchExpanded({ bbox: [10, 53.55, 10, 53.55], width: 0, height: 0, center: CENTER, zoom: ZOOM }),
    ).toBe(false)
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

    expect(isPitchExpanded({ bbox, width: WIDTH, height: HEIGHT, center: CENTER, zoom: wideZoom })).toBe(false)
  })
})
