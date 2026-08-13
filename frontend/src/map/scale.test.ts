import { describe, expect, it } from 'vitest'
import { computeScaleBar, metersPerPixel, niceDistance } from './scale'

describe('niceDistance', () => {
  it('rundet auf die nächste 1/2/5-Stufe ab', () => {
    expect(niceDistance(73)).toBe(50)
    expect(niceDistance(430)).toBe(200)
    expect(niceDistance(280)).toBe(200)
    expect(niceDistance(1)).toBe(1)
    expect(niceDistance(0)).toBe(0)
  })

  /**
   * The property the scale bar depends on: the rounded value must fit in the room it was
   * given. Rounding to the nearest step instead used to return 500 for 430 -- a bar 16%
   * wider than its own maximum.
   */
  it('gibt nie mehr zurück, als hineingegeben wurde', () => {
    for (let meters = 1; meters < 20_000; meters += 7) {
      expect(niceDistance(meters)).toBeLessThanOrEqual(meters)
    }
  })
})

describe('metersPerPixel', () => {
  /**
   * MapLibre's world is `512 * 2^zoom` pixels wide, so zoom 0 is 78271 m/px at the
   * equator -- not the 156543 of Leaflet's and Google's 256 px tables. Measured against
   * `map.unproject` on the running map: 0.3537 m/px at zoom 17 in Hamburg.
   */
  it('folgt MapLibres Kachelgröße von 512 Pixeln', () => {
    expect(metersPerPixel(0, 0)).toBeCloseTo(78271.51696, 4)
    expect(metersPerPixel(53.629, 17)).toBeCloseTo(0.3541, 3)
  })

  it('halbiert sich mit jeder Zoomstufe', () => {
    expect(metersPerPixel(53.6, 15)).toBeCloseTo(metersPerPixel(53.6, 14) / 2, 9)
  })
})

describe('computeScaleBar', () => {
  it('liefert eine Balkenbreite innerhalb des Maximums und ein passendes Label', () => {
    const bar = computeScaleBar(51.2, 12, 100)
    expect(bar.widthPx).toBeGreaterThan(0)
    expect(bar.widthPx).toBeLessThanOrEqual(100)
    expect(bar.label).toMatch(/^\d+(\.\d+)? (m|km)$/)
  })

  it('bleibt über alle Zoomstufen innerhalb des Maximums', () => {
    for (let zoom = 0; zoom <= 22; zoom += 0.5) {
      expect(computeScaleBar(53.55, zoom, 200).widthPx).toBeLessThanOrEqual(200)
    }
  })

  it('wechselt bei >= 1000 m auf km', () => {
    const bar = computeScaleBar(0, 2, 400)
    expect(bar.label.endsWith('km')).toBe(true)
  })

  /** The bar is a distance on the ground, so a step in must shorten what it stands for. */
  it('zeigt bei höherem Zoom eine kürzere Strecke', () => {
    const wide = computeScaleBar(53.55, 12, 200)
    const close = computeScaleBar(53.55, 16, 200)

    expect(close.label).not.toBe(wide.label)
  })
})
