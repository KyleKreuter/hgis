import { describe, expect, it } from 'vitest'
import {
  estimateImportDuration,
  exceedsWarningThreshold,
  formatDurationEstimate,
  WARNING_THRESHOLD,
} from './duration'

describe('exceedsWarningThreshold', () => {
  it('warnt erst, wenn die Grenze überschritten ist, nicht schon bei ihr', () => {
    expect(exceedsWarningThreshold(WARNING_THRESHOLD)).toBe(false)
    expect(exceedsWarningThreshold(WARNING_THRESHOLD + 1)).toBe(true)
  })

  it('warnt nicht bei unbekannter Objektzahl', () => {
    expect(exceedsWarningThreshold(null)).toBe(false)
  })

  it('kennt keine Obergrenze -- eine Million warnt genauso wie 100 001', () => {
    expect(exceedsWarningThreshold(1_000_000)).toBe(true)
  })
})

describe('estimateImportDuration', () => {
  it('rechnet eine Seite mit 10 000 Objekten mit dem gemessenen Bereich', () => {
    expect(estimateImportDuration(10_000)).toEqual({ minSeconds: 2.5, maxSeconds: 3.1 })
  })

  it('rundet auf volle Seiten auf', () => {
    expect(estimateImportDuration(10_001)).toEqual({ minSeconds: 5, maxSeconds: 6.2 })
  })

  it('rechnet das Straßenbaumkataster (229 876 Objekte, 23 Seiten) auf rund eine Minute', () => {
    const estimate = estimateImportDuration(229_876)
    expect(estimate.minSeconds).toBeCloseTo(57.5)
    expect(estimate.maxSeconds).toBeCloseTo(71.3)
  })
})

describe('formatDurationEstimate', () => {
  it('zeigt eine einzelne Zahl, wenn beide Enden auf dieselbe Sekunde runden', () => {
    expect(formatDurationEstimate({ minSeconds: 2.5, maxSeconds: 3.1 })).toBe('etwa 3 Sekunden')
  })

  it('zeigt eine Spanne in Sekunden unterhalb einer Minute', () => {
    expect(formatDurationEstimate({ minSeconds: 25, maxSeconds: 31 })).toBe('etwa 25–31 Sekunden')
  })

  it('wechselt oberhalb einer Minute auf Minuten', () => {
    expect(formatDurationEstimate({ minSeconds: 57.5, maxSeconds: 71.3 })).toBe('etwa 1 Minute')
    expect(formatDurationEstimate({ minSeconds: 150, maxSeconds: 250 })).toBe('etwa 3–4 Minuten')
  })

  it('schreibt eine einzelne Minute in der Einzahl', () => {
    expect(formatDurationEstimate({ minSeconds: 65, maxSeconds: 75 })).toBe('etwa 1 Minute')
  })
})
