import { describe, expect, it } from 'vitest'
import { buildGeoportalImportBody, type GeoportalImportSelection } from './importBody'

function selection(overrides: Partial<GeoportalImportSelection> = {}): GeoportalImportSelection {
  return {
    datasetId: 'strassenbaumkataster/strassenbaumkataster_hh',
    name: '',
    allFieldNames: ['baumid', 'gattung', 'gid'],
    selectedFields: new Set(['baumid', 'gattung', 'gid']),
    useMapExtent: false,
    mapBbox: null,
    ...overrides,
  }
}

describe('buildGeoportalImportBody', () => {
  it('trägt immer die datasetId', () => {
    expect(buildGeoportalImportBody(selection()).datasetId).toBe(
      'strassenbaumkataster/strassenbaumkataster_hh',
    )
  })

  it('lässt name weg, wenn er leer oder nur Leerzeichen ist', () => {
    expect(buildGeoportalImportBody(selection({ name: '' })).name).toBeUndefined()
    expect(buildGeoportalImportBody(selection({ name: '   ' })).name).toBeUndefined()
  })

  it('trimmt einen gesetzten Namen', () => {
    expect(buildGeoportalImportBody(selection({ name: '  Straßenbäume  ' })).name).toBe('Straßenbäume')
  })

  it('lässt bbox weg, solange der Kartenausschnitt-Schalter aus ist, auch mit bekannter bbox', () => {
    const body = buildGeoportalImportBody(
      selection({ useMapExtent: false, mapBbox: [9.99, 53.55, 10.0, 53.56] }),
    )
    expect(body.bbox).toBeUndefined()
  })

  it('lässt bbox weg, wenn der Schalter an ist, aber noch keine bbox bekannt ist', () => {
    const body = buildGeoportalImportBody(selection({ useMapExtent: true, mapBbox: null }))
    expect(body.bbox).toBeUndefined()
  })

  it('setzt bbox, wenn der Schalter an ist und eine bbox vorliegt', () => {
    const body = buildGeoportalImportBody(
      selection({ useMapExtent: true, mapBbox: [9.99, 53.55, 10.0, 53.56] }),
    )
    expect(body.bbox).toEqual([9.99, 53.55, 10.0, 53.56])
  })

  it('lässt fields weg, wenn alle Felder ausgewählt sind (Nutzerentscheidung E2)', () => {
    const body = buildGeoportalImportBody(
      selection({
        allFieldNames: ['baumid', 'gattung', 'gid'],
        selectedFields: new Set(['baumid', 'gattung', 'gid']),
      }),
    )
    expect(body.fields).toBeUndefined()
  })

  it('trägt nur die abgehakten Felder ein, in der Reihenfolge des Datensatzes', () => {
    const body = buildGeoportalImportBody(
      selection({
        allFieldNames: ['baumid', 'gattung', 'gid'],
        selectedFields: new Set(['gid', 'baumid']),
      }),
    )
    expect(body.fields).toEqual(['baumid', 'gid'])
  })

  it('ignoriert einen verwaisten Eintrag in der Auswahl, der kein echtes Feld mehr ist', () => {
    const body = buildGeoportalImportBody(
      selection({
        allFieldNames: ['baumid', 'gattung', 'gid'],
        selectedFields: new Set(['baumid', 'gattung', 'gid', 'irgendwas-veraltetes']),
      }),
    )
    // Alle drei echten Felder sind abgehakt -- der verwaiste Eintrag darf das nicht verdecken.
    expect(body.fields).toBeUndefined()
  })

  it('leert fields, wenn keines abgehakt ist', () => {
    const body = buildGeoportalImportBody(
      selection({ allFieldNames: ['baumid', 'gattung'], selectedFields: new Set() }),
    )
    expect(body.fields).toEqual([])
  })
})
