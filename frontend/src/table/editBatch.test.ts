import { describe, expect, it } from 'vitest'
import { buildUpdates } from './editBatch'

describe('buildUpdates', () => {
  it('baut ein Update pro geänderter Zeile mit ihrer rowVersion', () => {
    const edits = new Map([
      [42, { hoehe: 12.5, notiz: null }],
      [7, { name: 'Neu' }],
    ])
    const rowVersions = new Map([
      [42, '8241'],
      [7, '19'],
    ])

    expect(buildUpdates(edits, rowVersions)).toEqual([
      { fid: 42, rowVersion: '8241', properties: { hoehe: 12.5, notiz: null } },
      { fid: 7, rowVersion: '19', properties: { name: 'Neu' } },
    ])
  })

  it('liefert eine leere Liste für einen leeren Puffer', () => {
    expect(buildUpdates(new Map(), new Map())).toEqual([])
  })

  it('nimmt nur geänderte Spalten mit, nicht die ganze Zeile', () => {
    const edits = new Map([[1, { a: 'x' }]])
    const [update] = buildUpdates(edits, new Map([[1, 'v1']]))
    expect(Object.keys(update.properties!)).toEqual(['a'])
  })

  it('lässt rowVersion weg, wenn keine erfasst wurde', () => {
    const edits = new Map([[1, { a: 'x' }]])
    const [update] = buildUpdates(edits, new Map())
    expect(update.rowVersion).toBeUndefined()
  })
})
