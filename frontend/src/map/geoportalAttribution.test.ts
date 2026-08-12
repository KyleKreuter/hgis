import { describe, expect, it } from 'vitest'
import type { LayerSummary, LayerSource } from '@/api/layers'
import { combinedAttributionParts, distinctVisibleAttributions } from './geoportalAttribution'

function source(overrides: Partial<LayerSource> = {}): LayerSource {
  return {
    attribution: 'Freie und Hansestadt Hamburg, Behörde für Umwelt, Klima, Energie und Agrarwirtschaft',
    licenseName: 'Datenlizenz Deutschland – Namensnennung – Version 2.0',
    licenseUrl: 'https://www.govdata.de/dl-de/by-2-0',
    datasetUri: 'https://registry.gdi-de.org/id/de.hh/abc',
    metadataUrl: 'https://metaver.de/trefferanzeige?docuuid=abc',
    datasetId: 'strassenbaumkataster/strassenbaumkataster_hh',
    featureIdField: 'gid',
    fetchedAt: '2026-08-12T09:14:00Z',
    ...overrides,
  }
}

function layer(overrides: Partial<LayerSummary> = {}): LayerSummary {
  return {
    id: 'layer-1',
    name: 'Straßenbäume',
    geometryType: 'MULTIPOINT',
    srid: 25832,
    featureCount: 229_876,
    visible: true,
    zIndex: 0,
    minZoom: 0,
    maxZoom: 22,
    dataVersion: 1,
    styleVersion: 1,
    extent: null,
    source: source(),
    ...overrides,
  }
}

describe('distinctVisibleAttributions', () => {
  it('nimmt eine sichtbare Geoportal-Ebene auf', () => {
    expect(distinctVisibleAttributions([layer()])).toEqual([
      { attribution: source().attribution, licenseUrl: source().licenseUrl },
    ])
  })

  it('lässt Ebenen ohne Geoportal-Herkunft aus', () => {
    expect(distinctVisibleAttributions([layer({ source: null })])).toEqual([])
  })

  it('lässt ausgeblendete Geoportal-Ebenen aus -- nicht sichtbar heißt nicht kreditiert', () => {
    expect(distinctVisibleAttributions([layer({ visible: false })])).toEqual([])
  })

  it('nennt denselben Bereitsteller nur einmal, auch bei mehreren Layern', () => {
    const result = distinctVisibleAttributions([
      layer({ id: 'a' }),
      layer({ id: 'b' }),
    ])
    expect(result).toHaveLength(1)
  })

  it('unterscheidet Behörden und sortiert alphabetisch', () => {
    const bukea = layer({ id: 'a', source: source({ attribution: 'Freie und Hansestadt Hamburg, BUKEA' }) })
    const lgv = layer({ id: 'b', source: source({ attribution: 'Freie und Hansestadt Hamburg, LGV' }) })
    expect(distinctVisibleAttributions([lgv, bukea]).map((entry) => entry.attribution)).toEqual([
      'Freie und Hansestadt Hamburg, BUKEA',
      'Freie und Hansestadt Hamburg, LGV',
    ])
  })
})

describe('combinedAttributionParts', () => {
  it('gibt die Basiskarten-Namensnennung unverändert zurück, wenn keine Geoportal-Ebene sichtbar ist', () => {
    const basemap = [{ text: '© ' }, { text: 'OpenStreetMap', href: 'https://osm.org' }]
    expect(combinedAttributionParts(basemap, [])).toEqual(basemap)
  })

  it('hängt einen Eintrag mit verlinkter Kurzform der Lizenz an', () => {
    const parts = combinedAttributionParts([], [{ attribution: 'BUKEA', licenseUrl: 'https://example.org/lizenz' }])
    expect(parts).toEqual([
      { text: 'BUKEA' },
      { text: ' (' },
      { text: 'dl-de/by-2-0', href: 'https://example.org/lizenz' },
      { text: ')' },
    ])
  })

  it('setzt einen Trenner nur zwischen Basiskarte und Geoportal-Vermerk, nie davor', () => {
    const basemap = [{ text: '© OpenStreetMap' }]
    const parts = combinedAttributionParts(basemap, [{ attribution: 'BUKEA', licenseUrl: 'https://x' }])
    expect(parts[0]).toEqual({ text: '© OpenStreetMap' })
    expect(parts[1]).toEqual({ text: ' · ' })
  })

  it('trennt mehrere Geoportal-Einträge ebenfalls mit einem Punkt', () => {
    const parts = combinedAttributionParts(
      [],
      [
        { attribution: 'BUKEA', licenseUrl: 'https://x' },
        { attribution: 'LGV', licenseUrl: 'https://y' },
      ],
    )
    const separators = parts.filter((part) => part.text === ' · ')
    expect(separators).toHaveLength(1)
  })
})
