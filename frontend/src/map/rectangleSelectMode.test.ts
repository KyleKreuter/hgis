import { describe, expect, it } from 'vitest'
import { modifierSelectionMode } from './rectangleSelectMode'

describe('modifierSelectionMode', () => {
  it('ersetzt ohne gehaltene Taste', () => {
    expect(modifierSelectionMode({ shiftKey: false, altKey: false })).toBe('replace')
  })

  it('ergänzt mit Shift', () => {
    expect(modifierSelectionMode({ shiftKey: true, altKey: false })).toBe('add')
  })

  it('zieht mit Alt ab', () => {
    expect(modifierSelectionMode({ shiftKey: false, altKey: true })).toBe('subtract')
  })

  it('bevorzugt Shift, wenn beide Tasten gehalten werden', () => {
    expect(modifierSelectionMode({ shiftKey: true, altKey: true })).toBe('add')
  })
})
