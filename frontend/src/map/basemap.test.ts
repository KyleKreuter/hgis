import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  BASEMAPS,
  DEFAULT_BASEMAP_ID,
  attributionText,
  basemapChange,
  buildBasemapStyle,
  isBasemapId,
  resolveBasemap,
  resolveBasemapId,
} from './basemap'

describe('the basemap catalog', () => {
  it('offers OSM, a light and a dark variant, and no basemap at all', () => {
    expect(BASEMAPS.map((basemap) => basemap.id)).toEqual([
      'osm',
      'osm-light',
      'osm-dark',
      'opentopo',
      'none',
    ])
  })

  it('labels every entry in German', () => {
    for (const basemap of BASEMAPS) {
      expect(basemap.label.length).toBeGreaterThan(0)
      expect(basemap.hint.length).toBeGreaterThan(0)
    }
    expect(resolveBasemap('none').label).toBe('Keine Hintergrundkarte')
  })

  it('scopes every source and layer id to the basemap namespace', () => {
    for (const basemap of BASEMAPS) {
      for (const sourceId of Object.keys(basemap.sources)) {
        expect(isBasemapId(sourceId)).toBe(true)
      }
      for (const layer of basemap.layers) {
        expect(isBasemapId(layer.id)).toBe(true)
        expect(basemap.sources[layer.source]).toBeDefined()
      }
    }
  })

  it('serves every tile over https and without an API key', () => {
    for (const basemap of BASEMAPS) {
      for (const source of Object.values(basemap.sources)) {
        for (const tileUrl of source.tiles ?? []) {
          expect(tileUrl).toMatch(/^https:\/\//)
          expect(tileUrl).not.toMatch(/\?|api[_-]?key|access_token|\{s\}/i)
        }
      }
    }
  })

  it('credits the actual tile provider, and nobody for an empty background', () => {
    expect(attributionText(resolveBasemap('osm').attribution)).toBe('© OpenStreetMap contributors')
    expect(attributionText(resolveBasemap('osm-dark').attribution)).toBe(
      '© OpenStreetMap contributors',
    )
    // Verbatim as https://opentopomap.org/about#verwendung requires it.
    expect(attributionText(resolveBasemap('opentopo').attribution)).toBe(
      'Kartendaten: © OpenStreetMap-Mitwirkende, SRTM | Kartendarstellung: © OpenTopoMap (CC-BY-SA)',
    )
    expect(resolveBasemap('none').attribution).toEqual([])
  })

  /**
   * The licences ask for a link, not for a name in prose: a notice nobody can follow
   * credits nobody. The notice is therefore split into runs, and the ones naming a
   * project or a licence carry a URL.
   */
  it('makes the credited projects and licences reachable', () => {
    expect(resolveBasemap('osm').attribution).toContainEqual({
      text: 'OpenStreetMap',
      href: 'https://www.openstreetmap.org/copyright',
    })

    const opentopo = resolveBasemap('opentopo').attribution
    expect(opentopo.filter((part) => part.href).map((part) => part.text)).toEqual([
      'OpenStreetMap-Mitwirkende',
      'OpenTopoMap',
      'CC-BY-SA',
    ])
  })

  it('links only over https, and every basemap that credits somebody links somewhere', () => {
    for (const basemap of BASEMAPS) {
      for (const part of basemap.attribution) {
        expect(part.text.length).toBeGreaterThan(0)
        if (part.href) expect(part.href).toMatch(/^https:\/\//)
      }
      if (basemap.attribution.length > 0) {
        expect(basemap.attribution.some((part) => part.href)).toBe(true)
      }
    }
  })

  it('hands MapLibre the same notice as plain text on the source', () => {
    for (const basemap of BASEMAPS) {
      const expected = attributionText(basemap.attribution)
      for (const source of Object.values(basemap.sources)) {
        expect(source.attribution).toBe(expected)
      }
    }
  })

  it('renders the light and dark variants from the OSM tiles via paint properties', () => {
    const light = resolveBasemap('osm-light')
    const dark = resolveBasemap('osm-dark')

    for (const variant of [light, dark]) {
      expect(Object.values(variant.sources)[0].tiles).toEqual(
        resolveBasemap('osm').sources['basemap:osm'].tiles,
      )
      expect(variant.layers[0].paint).toBeDefined()
      expect(variant.hint).toMatch(/Darstellungsvariante/)
    }
    expect(light.layers[0].paint?.['raster-brightness-min']).toBeGreaterThan(0)
    expect(dark.layers[0].paint?.['raster-brightness-max']).toBeLessThan(1)
  })
})

describe('resolveBasemap', () => {
  it('falls back to OSM for an unknown, empty or missing id', () => {
    for (const id of ['', 'osm-satellite', 'positron', 'OSM', null, undefined]) {
      expect(resolveBasemapId(id)).toBe(DEFAULT_BASEMAP_ID)
    }
    expect(DEFAULT_BASEMAP_ID).toBe('osm')
  })

  it('returns the entry itself for every catalog id', () => {
    for (const basemap of BASEMAPS) {
      expect(resolveBasemap(basemap.id)).toBe(basemap)
    }
  })
})

describe('basemapChange', () => {
  it('reports nothing to persist when the stored id is already the chosen one', () => {
    expect(basemapChange('osm-dark', 'osm-dark')).toBeNull()
  })

  it('reports the chosen id when it differs', () => {
    expect(basemapChange('osm', 'none')).toBe('none')
    expect(basemapChange(null, 'opentopo')).toBe('opentopo')
  })

  it('normalises a stored id the catalog does not know', () => {
    // The user sees the OSM fallback and picks OSM: the stale value has to go, or it
    // stays in the project forever.
    expect(basemapChange('positron', 'osm')).toBe('osm')
  })

  it('never persists an id outside the catalog', () => {
    // An unknown choice can only ever resolve to the OSM fallback, so it either
    // changes nothing or writes exactly that.
    expect(basemapChange('osm', 'something-else')).toBeNull()
    expect(basemapChange('none', 'something-else')).toBe('osm')
  })
})

describe('buildBasemapStyle', () => {
  beforeEach(() => {
    vi.stubGlobal('window', { location: { origin: 'http://localhost:5173' } })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('points glyphs at an absolute local backend URL, not an external host', () => {
    const style = buildBasemapStyle()
    expect(style.glyphs).toBe('http://localhost:5173/api/glyphs/{fontstack}/{range}.pbf')
    expect(style.glyphs).not.toMatch(/openmaptiles|maptiler|fonts\.google/i)
  })

  it('keeps the self-hosted glyphs for every basemap, including none', () => {
    for (const basemap of BASEMAPS) {
      expect(buildBasemapStyle(basemap.id).glyphs).toBe(
        'http://localhost:5173/api/glyphs/{fontstack}/{range}.pbf',
      )
    }
  })

  it('builds the style of the requested basemap', () => {
    const style = buildBasemapStyle('opentopo')
    expect(style.layers.map((layer) => layer.id)).toEqual(['basemap:opentopo'])
    expect(Object.keys(style.sources)).toEqual(['basemap:opentopo'])
  })

  it('builds an empty but valid style for "no basemap"', () => {
    const style = buildBasemapStyle('none')
    expect(style.layers).toEqual([])
    expect(style.sources).toEqual({})
    expect(style.version).toBe(8)
  })

  it('falls back to OSM instead of rendering nothing for an unknown id', () => {
    expect(buildBasemapStyle('stamen-toner').layers.map((layer) => layer.id)).toEqual(['basemap:osm'])
  })
})
