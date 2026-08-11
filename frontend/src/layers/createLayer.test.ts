import { describe, expect, it } from 'vitest'
import { buildCreateLayerInput, canSubmitLayer, fieldNameErrors, MAX_FIELDS } from './createLayer'

describe('fieldNameErrors', () => {
  it('has no errors for distinct, non-blank names', () => {
    expect(fieldNameErrors([{ name: 'Art' }, { name: 'Pflanzjahr' }])).toEqual([
      undefined,
      undefined,
    ])
  })

  it('flags a blank name', () => {
    expect(fieldNameErrors([{ name: '' }, { name: '   ' }])).toEqual([
      'Pflichtfeld',
      'Pflichtfeld',
    ])
  })

  it('flags every row sharing a name, case-insensitively and after trimming', () => {
    expect(fieldNameErrors([{ name: 'Art' }, { name: ' art ' }, { name: 'Höhe' }])).toEqual([
      'Feldname bereits vergeben',
      'Feldname bereits vergeben',
      undefined,
    ])
  })

  it('does not let two blank rows count as duplicates of each other', () => {
    expect(fieldNameErrors([{ name: '' }, { name: '' }])).toEqual(['Pflichtfeld', 'Pflichtfeld'])
  })

  it('returns an empty array for an empty field list', () => {
    expect(fieldNameErrors([])).toEqual([])
  })
})

describe('canSubmitLayer', () => {
  it('requires a non-blank layer name', () => {
    expect(canSubmitLayer('', [])).toBe(false)
    expect(canSubmitLayer('   ', [])).toBe(false)
    expect(canSubmitLayer('Baumkataster', [])).toBe(true)
  })

  it('rejects a duplicate field name', () => {
    expect(canSubmitLayer('Baumkataster', [{ name: 'Art' }, { name: 'art' }])).toBe(false)
  })

  it('rejects a blank field name', () => {
    expect(canSubmitLayer('Baumkataster', [{ name: '' }])).toBe(false)
  })

  it('rejects more than MAX_FIELDS rows', () => {
    const tooMany = Array.from({ length: MAX_FIELDS + 1 }, (_, i) => ({ name: `Feld${i}` }))
    expect(canSubmitLayer('Baumkataster', tooMany)).toBe(false)
  })

  it('accepts exactly MAX_FIELDS distinct, non-blank rows', () => {
    const exactly = Array.from({ length: MAX_FIELDS }, (_, i) => ({ name: `Feld${i}` }))
    expect(canSubmitLayer('Baumkataster', exactly)).toBe(true)
  })

  it('accepts an empty field list -- a layer without attribute fields is valid', () => {
    expect(canSubmitLayer('Baumkataster', [])).toBe(true)
  })
})

describe('buildCreateLayerInput', () => {
  it('trims the layer name and every field name', () => {
    expect(
      buildCreateLayerInput('  Baumkataster  ', 'MULTIPOINT', [
        { name: '  Art  ', type: 'TEXT' },
        { name: 'Pflanzjahr', type: 'INTEGER' },
      ]),
    ).toEqual({
      name: 'Baumkataster',
      geometryType: 'MULTIPOINT',
      fields: [
        { name: 'Art', type: 'TEXT' },
        { name: 'Pflanzjahr', type: 'INTEGER' },
      ],
    })
  })

  it('keeps an empty field list empty', () => {
    expect(buildCreateLayerInput('Baumkataster', 'MULTIPOLYGON', [])).toEqual({
      name: 'Baumkataster',
      geometryType: 'MULTIPOLYGON',
      fields: [],
    })
  })
})
