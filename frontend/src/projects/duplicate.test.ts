import { describe, expect, it } from 'vitest'
import { duplicateNameInput } from './duplicate'

describe('duplicateNameInput', () => {
  it('omits the untouched suggestion so the backend can choose a collision-safe name', () => {
    expect(duplicateNameInput('Bestand', 'Bestand (Kopie)')).toEqual({})
  })

  it('preserves an explicitly edited target name', () => {
    expect(duplicateNameInput('Bestand', 'Bestand – Szenario B')).toEqual({
      name: 'Bestand – Szenario B',
    })
  })
})
