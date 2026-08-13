import { describe, expect, it } from 'vitest'
import type { GeoportalCollection, GeoportalDatasetSummary } from '@/api/geoportal'
import {
  activeDatasetId,
  isServiceEntry,
  matchesCollectionQuery,
  needsCollectionChoice,
  visibleCollections,
} from './collections'

function dataset(overrides: Partial<GeoportalDatasetSummary> = {}): GeoportalDatasetSummary {
  return {
    id: 'strassenbaumkataster/strassenbaumkataster_hh',
    title: 'Straßenbaumkataster Hamburg',
    description: null,
    kind: 'FEATURES',
    agency: 'BUKEA',
    topic: 'Umwelt',
    featureCount: 229_876,
    bbox: null,
    collectionCount: 1,
    wmsUrl: null,
    ...overrides,
  }
}

function collection(title: string, id = title): GeoportalCollection {
  return { id, title }
}

describe('isServiceEntry', () => {
  it('erkennt einen Dienst an mehr als einer Sammlung', () => {
    expect(isServiceEntry(dataset({ collectionCount: 247 }))).toBe(true)
    expect(isServiceEntry(dataset({ collectionCount: 2 }))).toBe(true)
  })

  it('lässt den flachen Eintrag ein Datensatz bleiben', () => {
    expect(isServiceEntry(dataset({ collectionCount: 1 }))).toBe(false)
  })
})

describe('needsCollectionChoice', () => {
  it('verlangt eine Wahl, solange beim Dienst keine Sammlung gewählt ist', () => {
    expect(needsCollectionChoice(dataset({ collectionCount: 247 }), null)).toBe(true)
  })

  it('ist erfüllt, sobald eine Sammlung gewählt ist', () => {
    expect(needsCollectionChoice(dataset({ collectionCount: 247 }), 'xplan/bp_plan')).toBe(false)
  })

  it('verlangt beim flachen Eintrag nie eine Wahl', () => {
    expect(needsCollectionChoice(dataset(), null)).toBe(false)
  })

  it('verlangt ohne Auswahl in der Liste nichts', () => {
    expect(needsCollectionChoice(null, null)).toBe(false)
  })
})

describe('activeDatasetId', () => {
  it('nimmt die gewählte Sammlung, nicht den Dienst', () => {
    expect(activeDatasetId('xplan', 'xplan/bp_plan')).toBe('xplan/bp_plan')
  })

  it('nimmt beim flachen Eintrag dessen eigene Kennung', () => {
    expect(activeDatasetId('strassenbaumkataster/strassenbaumkataster_hh', null)).toBe(
      'strassenbaumkataster/strassenbaumkataster_hh',
    )
  })

  it('bleibt ohne Auswahl leer', () => {
    expect(activeDatasetId(null, null)).toBeNull()
  })
})

describe('matchesCollectionQuery', () => {
  it('findet Wortteile ohne Rücksicht auf Groß- und Kleinschreibung', () => {
    expect(matchesCollectionQuery(collection('BWI - Hafennutzungsgebiet'), 'hafen')).toBe(true)
    expect(matchesCollectionQuery(collection('BWI - Hafennutzungsgebiet'), 'HAFEN')).toBe(true)
  })

  it('lehnt ab, was im Titel nicht vorkommt', () => {
    expect(matchesCollectionQuery(collection('BWI - Hafennutzungsgebiet'), 'Baum')).toBe(false)
  })

  it('behandelt eine leere Suche als Treffer für alles', () => {
    expect(matchesCollectionQuery(collection('Irgendetwas'), '   ')).toBe(true)
  })
})

describe('visibleCollections', () => {
  const all = [collection('Wasserflächen'), collection('Änderungen'), collection('Hafennutzung')]

  it('sortiert nach deutscher Kollation', () => {
    expect(visibleCollections(all, '').map((entry) => entry.title)).toEqual([
      'Änderungen',
      'Hafennutzung',
      'Wasserflächen',
    ])
  })

  it('engt auf die Treffer der Suche ein', () => {
    expect(visibleCollections(all, 'flächen').map((entry) => entry.title)).toEqual(['Wasserflächen'])
  })

  it('lässt das Original unverändert', () => {
    visibleCollections(all, '')
    expect(all.map((entry) => entry.title)).toEqual(['Wasserflächen', 'Änderungen', 'Hafennutzung'])
  })
})
