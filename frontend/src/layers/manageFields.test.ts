import { describe, expect, it } from 'vitest'
import type { LayerFieldUsage } from '@/api/layers'
import {
  buildAddFieldInput,
  buildDeleteFieldWarning,
  buildRenameFieldInput,
  existingFieldNameError,
  type ExistingField,
} from './manageFields'

const existing: ExistingField[] = [
  { id: 'f1', sourceName: 'Art', columnName: 'art' },
  { id: 'f2', sourceName: 'Größe', columnName: 'groesse' },
]

describe('existingFieldNameError', () => {
  it('has no error for a name that matches nothing', () => {
    expect(existingFieldNameError('Pflanzjahr', existing)).toBeUndefined()
  })

  it('flags a blank name', () => {
    expect(existingFieldNameError('', existing)).toBe('Pflichtfeld')
    expect(existingFieldNameError('   ', existing)).toBe('Pflichtfeld')
  })

  it('flags a name colliding with another field\'s sourceName, case-insensitively and after trimming', () => {
    expect(existingFieldNameError(' art ', existing)).toBe('Feldname bereits vergeben')
    expect(existingFieldNameError('ART', existing)).toBe('Feldname bereits vergeben')
  })

  it("flags a name colliding with another field's columnName -- CONTRACT.md trap 2", () => {
    // "Groesse" is nobody's sourceName, but it is f2's columnName -- accepting it would
    // let the server's name resolution silently retarget onto f2 later.
    expect(existingFieldNameError('Groesse', existing)).toBe('Feldname bereits vergeben')
  })

  it('allows a field to keep its own previous name unchanged (rename no-op)', () => {
    expect(existingFieldNameError('Art', existing, 'f1')).toBeUndefined()
    expect(existingFieldNameError(' art ', existing, 'f1')).toBeUndefined()
  })

  it('still rejects renaming onto a different field\'s name even when excluding self', () => {
    expect(existingFieldNameError('Größe', existing, 'f1')).toBe('Feldname bereits vergeben')
  })

  it('returns no error against an empty existing list', () => {
    expect(existingFieldNameError('Irgendwas', [])).toBeUndefined()
  })
})

describe('buildAddFieldInput', () => {
  it('trims the name and keeps the chosen type', () => {
    expect(buildAddFieldInput('  Pflanzjahr  ', 'INTEGER')).toEqual({
      name: 'Pflanzjahr',
      type: 'INTEGER',
    })
  })
})

describe('buildRenameFieldInput', () => {
  it('trims the name and keeps the field id', () => {
    expect(buildRenameFieldInput('f1', '  Baumart  ')).toEqual({
      fieldId: 'f1',
      name: 'Baumart',
    })
  })
})

describe('buildDeleteFieldWarning', () => {
  const usage = (overrides: Partial<LayerFieldUsage> = {}): LayerFieldUsage => ({
    valueCount: 0,
    usedByRenderer: false,
    usedByLabels: false,
    ...overrides,
  })

  it('names zero affected objects explicitly rather than saying "0 Objekte"', () => {
    expect(buildDeleteFieldWarning(usage())).toBe('Kein Objekt hat einen Wert in diesem Feld.')
  })

  it('uses the singular for exactly one affected object', () => {
    expect(buildDeleteFieldWarning(usage({ valueCount: 1 }))).toBe(
      '1 Objekt hat einen Wert in diesem Feld.',
    )
  })

  it('uses the plural and German grouping for several affected objects', () => {
    expect(buildDeleteFieldWarning(usage({ valueCount: 1234 }))).toBe(
      '1.234 Objekte haben einen Wert in diesem Feld.',
    )
  })

  it('warns that the renderer is reset when the field drives it', () => {
    expect(buildDeleteFieldWarning(usage({ valueCount: 5, usedByRenderer: true }))).toBe(
      '5 Objekte haben einen Wert in diesem Feld. Die Einfärbung nach diesem Feld wird dabei zurückgesetzt.',
    )
  })

  it('warns that labels are disabled when the field drives them', () => {
    expect(buildDeleteFieldWarning(usage({ valueCount: 5, usedByLabels: true }))).toBe(
      '5 Objekte haben einen Wert in diesem Feld. Die Beschriftung nach diesem Feld wird dabei deaktiviert.',
    )
  })

  it('combines both warnings when the field drives renderer and labels at once', () => {
    expect(
      buildDeleteFieldWarning(usage({ valueCount: 5, usedByRenderer: true, usedByLabels: true })),
    ).toBe(
      '5 Objekte haben einen Wert in diesem Feld. Die Einfärbung nach diesem Feld wird dabei zurückgesetzt. Die Beschriftung nach diesem Feld wird dabei deaktiviert.',
    )
  })
})
