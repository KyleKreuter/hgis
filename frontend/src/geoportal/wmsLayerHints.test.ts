import { describe, expect, it } from 'vitest'
import { formatWmsScaleLimits } from './wmsLayerHints'

describe('formatWmsScaleLimits', () => {
  it('nennt nichts, wenn der Dienst keine Grenze angibt', () => {
    expect(formatWmsScaleLimits(null, null)).toBeNull()
  })

  it('zeigt beide Grenzen mit einem Gedankenstrich', () => {
    expect(formatWmsScaleLimits(1000, 3000)).toBe('1:1.000 – 1:3.000')
  })

  it('zeigt nur die untere Grenze mit "ab"', () => {
    expect(formatWmsScaleLimits(1000, null)).toBe('ab 1:1.000')
  })

  it('zeigt nur die obere Grenze mit "bis"', () => {
    expect(formatWmsScaleLimits(null, 3000)).toBe('bis 1:3.000')
  })

  it('rundet und gruppiert nach deutscher Konvention', () => {
    expect(formatWmsScaleLimits(2500.4, null)).toBe('ab 1:2.500')
  })
})
