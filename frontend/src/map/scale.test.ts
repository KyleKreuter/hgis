import { describe, expect, it } from 'vitest'
import { computeScaleBar, niceDistance } from './scale'

describe('niceDistance', () => {
  it('rundet auf die nächste 1/2/5-Stufe ab', () => {
    expect(niceDistance(73)).toBe(50)
    expect(niceDistance(430)).toBe(500)
    expect(niceDistance(280)).toBe(200)
    expect(niceDistance(1)).toBe(1)
    expect(niceDistance(0)).toBe(0)
  })
})

describe('computeScaleBar', () => {
  it('liefert eine Balkenbreite innerhalb des Maximums und ein passendes Label', () => {
    const bar = computeScaleBar(51.2, 12, 100)
    expect(bar.widthPx).toBeGreaterThan(0)
    expect(bar.widthPx).toBeLessThanOrEqual(100)
    expect(bar.label).toMatch(/^\d+(\.\d+)? (m|km)$/)
  })

  it('wechselt bei >= 1000 m auf km', () => {
    const bar = computeScaleBar(0, 2, 400)
    expect(bar.label.endsWith('km')).toBe(true)
  })
})
