import { describe, expect, it } from 'vitest'
import { formatFieldValues, isImportable } from './fields'

describe('formatFieldValues', () => {
  it('nennt eine leere Liste ausdrücklich', () => {
    expect(formatFieldValues([])).toBe('keine Werte')
  })

  it('zeigt alle Werte, wenn höchstens drei vorliegen', () => {
    expect(formatFieldValues(['Abies / Tanne'])).toBe('Abies / Tanne')
    expect(formatFieldValues(['A', 'B', 'C'])).toBe('A, B, C')
  })

  it('kappt nach drei Werten und markiert, dass mehr vorhanden sind', () => {
    expect(formatFieldValues(['A', 'B', 'C', 'D'])).toBe('A, B, C …')
  })
})

describe('isImportable', () => {
  it('erlaubt FEATURES und BOTH', () => {
    expect(isImportable('FEATURES')).toBe(true)
    expect(isImportable('BOTH')).toBe(true)
  })

  it('lehnt WMS ab -- der Bildweg kommt erst in Stufe 2', () => {
    expect(isImportable('WMS')).toBe(false)
  })
})
