import { describe, expect, it } from 'vitest'
import { defaultLabels, defaultStyleFor, defaultSymbolFor } from './defaults'
import { isPersistable } from './persistable'
import type { LayerStyle } from './types'

const BASE = defaultStyleFor('MULTIPOLYGON')

describe('isPersistable', () => {
  it('lässt das Zurücksetzen und einen vollständigen Style durch', () => {
    expect(isPersistable(null)).toBe(true)
    expect(isPersistable(BASE)).toBe(true)
  })

  it('hält einen klassifizierten Renderer ohne Feld zurück -- der Server lehnt ihn ab', () => {
    const categorized = (field: string): LayerStyle => ({
      ...BASE,
      renderer: {
        type: 'categorized',
        field,
        categories: [],
        fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      },
    })

    expect(isPersistable(categorized(''))).toBe(false)
    expect(isPersistable(categorized('art'))).toBe(true)
  })

  it('hält eine eingeschaltete Beschriftung ohne Feld zurück', () => {
    expect(isPersistable({ ...BASE, labels: defaultLabels('') })).toBe(false)
    expect(isPersistable({ ...BASE, labels: defaultLabels('name') })).toBe(true)
    // Switched off it does not matter what the field says.
    expect(isPersistable({ ...BASE, labels: { ...defaultLabels(''), enabled: false } })).toBe(true)
  })
})
