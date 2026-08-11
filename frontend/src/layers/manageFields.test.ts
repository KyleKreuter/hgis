import { describe, expect, it } from 'vitest'
import {
  buildAddFieldInput,
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
