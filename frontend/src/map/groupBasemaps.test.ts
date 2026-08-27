import { describe, expect, it } from 'vitest'
import type { BasemapCatalogEntry } from '@/api/basemaps'
import { groupBasemaps } from './groupBasemaps'
import { TEST_BASEMAP_CATALOG } from './testBasemapCatalog'

function entry(overrides: Partial<BasemapCatalogEntry> = {}): BasemapCatalogEntry {
  return {
    id: 'x',
    title: 'X',
    hint: '',
    group: 'Standard',
    urlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
    attribution: [],
    minZoom: 0,
    maxZoom: 19,
    coverage: 'world',
    requiresAccount: false,
    deprecated: false,
    paint: null,
    ...overrides,
  }
}

describe('groupBasemaps', () => {
  it('keeps every entry, split by its group field', () => {
    const groups = groupBasemaps(TEST_BASEMAP_CATALOG)
    const total = groups.reduce((sum, group) => sum + group.entries.length, 0)
    expect(total).toBe(TEST_BASEMAP_CATALOG.length)
  })

  it('orders groups by first appearance in the catalog, not alphabetically', () => {
    const catalog = [
      entry({ id: 'a', group: 'Thematisch' }),
      entry({ id: 'b', group: 'Standard' }),
      entry({ id: 'c', group: 'Thematisch' }),
      entry({ id: 'd', group: 'Deutschland' }),
    ]

    expect(groupBasemaps(catalog).map((group) => group.group)).toEqual([
      'Thematisch',
      'Standard',
      'Deutschland',
    ])
  })

  it('keeps every entry of a group together and in catalog order', () => {
    const catalog = [
      entry({ id: 'a', group: 'Standard' }),
      entry({ id: 'b', group: 'Deutschland' }),
      entry({ id: 'c', group: 'Standard' }),
    ]

    const groups = groupBasemaps(catalog)
    expect(groups.find((group) => group.group === 'Standard')?.entries.map((e) => e.id)).toEqual([
      'a',
      'c',
    ])
  })

  it('returns an empty list for an empty catalog', () => {
    expect(groupBasemaps([])).toEqual([])
  })

  it('groups every entry of the shared test catalog', () => {
    const groups = groupBasemaps(TEST_BASEMAP_CATALOG)
    expect(groups.map((group) => group.group)).toEqual([
      'Standard',
      'Gelände',
      'Deutschland',
      'Luft- und Satellitenbild',
      'Thematisch',
      'Bundesländer',
    ])
  })
})
