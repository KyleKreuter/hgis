import { describe, expect, it } from 'vitest'
import type { GeoportalDatasetSummary } from '@/api/geoportal'
import {
  defaultGeoportalFilters,
  defaultKindFilters,
  distinctAgencies,
  distinctTopics,
  filterDatasets,
  matchesQuery,
  sortByTitle,
} from './search'

function dataset(overrides: Partial<GeoportalDatasetSummary> = {}): GeoportalDatasetSummary {
  return {
    id: 'strassenbaumkataster/strassenbaumkataster_hh',
    title: 'Straßenbaumkataster Hamburg',
    description: 'Baumstandorte im öffentlichen Raum',
    kind: 'FEATURES',
    agency: 'BUKEA',
    topic: 'Umwelt',
    featureCount: 229_876,
    bbox: [8.42, 53.39, 10.33, 53.97],
    collectionCount: 1,
    ...overrides,
  }
}

describe('matchesQuery', () => {
  it('findet Wortteile im Namen, ohne auf Groß- und Kleinschreibung zu achten', () => {
    expect(matchesQuery(dataset(), 'baum')).toBe(true)
    expect(matchesQuery(dataset(), 'BAUM')).toBe(true)
    expect(matchesQuery(dataset(), 'Baumkataster')).toBe(true)
  })

  it('durchsucht auch Beschreibung und Behörde', () => {
    expect(matchesQuery(dataset(), 'öffentlichen Raum')).toBe(true)
    expect(matchesQuery(dataset(), 'bukea')).toBe(true)
  })

  it('lehnt ab, was in keinem der drei Felder vorkommt', () => {
    expect(matchesQuery(dataset(), 'Flurstück')).toBe(false)
  })

  it('kommt mit einer fehlenden Beschreibung zurecht, statt zu werfen', () => {
    expect(matchesQuery(dataset({ description: null }), 'baum')).toBe(true)
    expect(matchesQuery(dataset({ description: null }), 'unbekannt')).toBe(false)
  })

  it('behandelt eine leere Suche als Treffer für alles', () => {
    expect(matchesQuery(dataset(), '')).toBe(true)
    expect(matchesQuery(dataset(), '   ')).toBe(true)
  })
})

describe('filterDatasets', () => {
  const features = dataset({ id: 'a', title: 'Straßenbaumkataster', kind: 'FEATURES', topic: 'Umwelt', agency: 'BUKEA' })
  const both = dataset({ id: 'b', title: 'Flurstücke', kind: 'BOTH', topic: 'Regionen und Städte', agency: 'LGV' })
  const wms = dataset({ id: 'c', title: 'Luftbilder', kind: 'WMS', topic: 'Umwelt', agency: 'LGV' })
  const all = [features, both, wms]

  it('lässt bei den Standardfiltern den ganzen Katalog durch', () => {
    expect(filterDatasets(all, defaultGeoportalFilters())).toEqual(all)
  })

  it('zählt BOTH als Objektdienst, nicht als reinen Bilddienst', () => {
    const onlyFeatures = filterDatasets(all, { ...defaultGeoportalFilters(), kinds: new Set(['features']) })
    expect(onlyFeatures).toEqual([features, both])

    const onlyImage = filterDatasets(all, { ...defaultGeoportalFilters(), kinds: new Set(['imageOnly']) })
    expect(onlyImage).toEqual([wms])
  })

  it('leert die Liste, wenn beide Art-Kästchen abgehakt sind', () => {
    expect(filterDatasets(all, { ...defaultGeoportalFilters(), kinds: new Set() })).toEqual([])
  })

  it('engt nach Thema ein', () => {
    expect(filterDatasets(all, { ...defaultGeoportalFilters(), topic: 'Umwelt' })).toEqual([features, wms])
  })

  it('engt nach Behörde ein', () => {
    expect(filterDatasets(all, { ...defaultGeoportalFilters(), agency: 'LGV' })).toEqual([both, wms])
  })

  it('kombiniert Suche, Art, Thema und Behörde mit UND', () => {
    const result = filterDatasets(all, {
      query: 'luft',
      kinds: defaultKindFilters(),
      topic: 'Umwelt',
      agency: 'LGV',
    })
    expect(result).toEqual([wms])
  })
})

describe('sortByTitle', () => {
  it('sortiert alphabetisch nach deutscher Kollation', () => {
    const unsorted = [dataset({ title: 'Zoo' }), dataset({ title: 'Ärzte' }), dataset({ title: 'Bäume' })]
    expect(sortByTitle(unsorted).map((entry) => entry.title)).toEqual(['Ärzte', 'Bäume', 'Zoo'])
  })

  it('lässt das Original unverändert', () => {
    const original = [dataset({ title: 'B' }), dataset({ title: 'A' })]
    sortByTitle(original)
    expect(original.map((entry) => entry.title)).toEqual(['B', 'A'])
  })
})

describe('distinctTopics / distinctAgencies', () => {
  const datasets = [
    dataset({ topic: 'Umwelt', agency: 'BUKEA' }),
    dataset({ topic: 'Verkehr', agency: 'LGV' }),
    dataset({ topic: 'Umwelt', agency: null }),
    dataset({ topic: null, agency: 'BUKEA' }),
  ]

  it('listet jeden Wert nur einmal, alphabetisch, ohne null', () => {
    expect(distinctTopics(datasets)).toEqual(['Umwelt', 'Verkehr'])
    expect(distinctAgencies(datasets)).toEqual(['BUKEA', 'LGV'])
  })
})
