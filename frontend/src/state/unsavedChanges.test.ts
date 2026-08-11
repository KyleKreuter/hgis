import { describe, expect, it } from 'vitest'
import {
  describeUnsavedChanges,
  hasUnsavedChanges,
  totalUnsavedChanges,
  unsavedChangesVerb,
} from './unsavedChanges'

describe('totalUnsavedChanges', () => {
  it('adds both modes together', () => {
    expect(totalUnsavedChanges(2, 3)).toBe(5)
  })

  it('is 0 when both modes are clean', () => {
    expect(totalUnsavedChanges(0, 0)).toBe(0)
  })

  it('counts a change from either mode alone', () => {
    expect(totalUnsavedChanges(4, 0)).toBe(4)
    expect(totalUnsavedChanges(0, 7)).toBe(7)
  })
})

describe('hasUnsavedChanges', () => {
  it('is false once neither buffer has anything pending', () => {
    expect(hasUnsavedChanges(0, 0)).toBe(false)
  })

  it('is true as soon as either buffer has something pending', () => {
    expect(hasUnsavedChanges(1, 0)).toBe(true)
    expect(hasUnsavedChanges(0, 1)).toBe(true)
    expect(hasUnsavedChanges(2, 3)).toBe(true)
  })
})

describe('describeUnsavedChanges', () => {
  it('uses the singular for exactly one change', () => {
    expect(describeUnsavedChanges(1)).toBe('1 ungespeicherte Änderung')
  })

  it('uses the plural for more than one change', () => {
    expect(describeUnsavedChanges(3)).toBe('3 ungespeicherte Änderungen')
  })

  it('uses the plural for zero too -- there is no "0th change"', () => {
    expect(describeUnsavedChanges(0)).toBe('0 ungespeicherte Änderungen')
  })
})

describe('unsavedChangesVerb', () => {
  it('is "geht" for exactly one change', () => {
    expect(unsavedChangesVerb(1)).toBe('geht')
  })

  it('is "gehen" for more than one, and for zero', () => {
    expect(unsavedChangesVerb(0)).toBe('gehen')
    expect(unsavedChangesVerb(2)).toBe('gehen')
  })
})
