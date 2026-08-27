import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DEFAULT_BASEMAP_ID,
  attributionText,
  basemapChange,
  buildBasemapStyle,
  isBasemapId,
  isCustomBasemapUrl,
  resolveBasemap,
  resolveBasemapId,
  validateBasemapUrlTemplate,
} from './basemap'
import { TEST_BASEMAP_CATALOG } from './testBasemapCatalog'

const CATALOG = TEST_BASEMAP_CATALOG

describe('resolveBasemap', () => {
  it('falls back to OSM for an unknown, empty or missing id', () => {
    for (const id of ['', 'osm-satellite', 'positron', 'OSM', null, undefined]) {
      expect(resolveBasemapId(CATALOG, id)).toBe(DEFAULT_BASEMAP_ID)
    }
    expect(DEFAULT_BASEMAP_ID).toBe('osm')
  })

  it('resolves every catalog entry by its id', () => {
    for (const entry of CATALOG) {
      expect(resolveBasemap(CATALOG, entry.id).id).toBe(entry.id)
    }
  })

  it('labels every entry in German', () => {
    for (const entry of CATALOG) {
      const resolved = resolveBasemap(CATALOG, entry.id)
      expect(resolved.label.length).toBeGreaterThan(0)
      expect(resolved.hint.length).toBeGreaterThan(0)
    }
    expect(resolveBasemap(CATALOG, 'none').label).toBe('Keine Hintergrundkarte')
  })

  it('scopes every source and layer id to the basemap namespace', () => {
    for (const entry of CATALOG) {
      const resolved = resolveBasemap(CATALOG, entry.id)
      for (const sourceId of Object.keys(resolved.sources)) {
        expect(isBasemapId(sourceId)).toBe(true)
      }
      for (const layer of resolved.layers) {
        expect(isBasemapId(layer.id)).toBe(true)
        expect(resolved.sources[layer.source]).toBeDefined()
      }
    }
  })

  it('serves every tile over https and without an API key', () => {
    for (const entry of CATALOG) {
      const resolved = resolveBasemap(CATALOG, entry.id)
      for (const source of Object.values(resolved.sources)) {
        for (const tileUrl of source.tiles ?? []) {
          expect(tileUrl).toMatch(/^https:\/\//)
          // A `?` alone is no longer a red flag on its own: a WMS-GetMap URL (VERTRAG.md
          // "Zwei Formen von urlTemplate") is one long query string by design.
          expect(tileUrl).not.toMatch(/api[_-]?key|access_token/i)
        }
      }
    }
  })

  it('credits the actual tile provider, and nobody for an empty background', () => {
    expect(attributionText(resolveBasemap(CATALOG, 'osm').attribution)).toBe(
      '© OpenStreetMap contributors',
    )
    expect(attributionText(resolveBasemap(CATALOG, 'osm-dark').attribution)).toBe(
      '© OpenStreetMap contributors',
    )
    expect(attributionText(resolveBasemap(CATALOG, 'opentopo').attribution)).toBe(
      'Kartendaten: © OpenStreetMap-Mitwirkende, SRTM | Kartendarstellung: © OpenTopoMap (CC-BY-SA)',
    )
    expect(resolveBasemap(CATALOG, 'none').attribution).toEqual([])
  })

  it('hands MapLibre the same notice as plain text on the source', () => {
    for (const entry of CATALOG) {
      const resolved = resolveBasemap(CATALOG, entry.id)
      const expected = attributionText(resolved.attribution)
      for (const source of Object.values(resolved.sources)) {
        expect(source.attribution).toBe(expected)
      }
    }
  })

  it('renders the light and dark variants from the OSM tiles via paint properties', () => {
    const light = resolveBasemap(CATALOG, 'osm-light')
    const dark = resolveBasemap(CATALOG, 'osm-dark')

    for (const variant of [light, dark]) {
      expect(Object.values(variant.sources)[0].tiles).toEqual(
        resolveBasemap(CATALOG, 'osm').sources['basemap:osm'].tiles,
      )
      expect(variant.layers[0].paint).toBeDefined()
    }
    expect(light.layers[0].paint?.['raster-brightness-min']).toBeGreaterThan(0)
    expect(dark.layers[0].paint?.['raster-brightness-max']).toBeLessThan(1)
  })

  it('carries requiresAccount, deprecated and coverage through untouched -- the picker reads them, this module does not act on them', () => {
    const esri = CATALOG.find((entry) => entry.id === 'esri-imagery')!
    expect(esri.requiresAccount).toBe(true)
    expect(esri.coverage).toBe('world')

    const grau = CATALOG.find((entry) => entry.id === 'basemapde-grau')!
    expect(grau.coverage).toBe('DE')

    const deprecated = CATALOG.find((entry) => entry.id === 'stamen-toner-relaunch')!
    expect(deprecated.deprecated).toBe(true)
  })
})

describe('isCustomBasemapUrl', () => {
  it('recognises a free-text tile URL', () => {
    expect(isCustomBasemapUrl('https://tiles.example.test/{z}/{x}/{y}.png')).toBe(true)
  })

  it('rejects a catalog id and empty values', () => {
    expect(isCustomBasemapUrl('osm')).toBe(false)
    expect(isCustomBasemapUrl('')).toBe(false)
    expect(isCustomBasemapUrl(null)).toBe(false)
    expect(isCustomBasemapUrl(undefined)).toBe(false)
  })
})

describe('validateBasemapUrlTemplate', () => {
  it('accepts a well-formed https URL with all three placeholders', () => {
    expect(validateBasemapUrlTemplate('https://tiles.example.test/{z}/{x}/{y}.png')).toBeNull()
  })

  it('names the empty field', () => {
    expect(validateBasemapUrlTemplate('')).toMatch(/erforderlich/)
    expect(validateBasemapUrlTemplate('   ')).toMatch(/erforderlich/)
  })

  it('rejects a URL that is not https', () => {
    expect(validateBasemapUrlTemplate('http://tiles.example.test/{z}/{x}/{y}.png')).toMatch(
      /https:\/\//,
    )
  })

  it('names each missing placeholder', () => {
    expect(validateBasemapUrlTemplate('https://tiles.example.test/{x}/{y}.png')).toMatch(/\{z\}/)
    expect(validateBasemapUrlTemplate('https://tiles.example.test/{z}/{y}.png')).toMatch(/\{x\}/)
    expect(validateBasemapUrlTemplate('https://tiles.example.test/{z}/{x}.png')).toMatch(/\{y\}/)
  })

  /**
   * VERTRAG.md "Zwei Formen von urlTemplate", nachgetragen nachdem sich zeigte, dass
   * die meisten Landesvermessungen nur WMS anbieten, kein WMTS -- die Hamburger
   * Luftbilder etwa.
   */
  it('accepts the WMS-GetMap form with {bbox-epsg-3857} instead of z/x/y', () => {
    const wms =
      'https://geodienste.hamburg.de/wms_dop?SERVICE=WMS&REQUEST=GetMap&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256'
    expect(validateBasemapUrlTemplate(wms)).toBeNull()
  })

  it('accepts a URL that somehow carries both placeholder kinds', () => {
    expect(
      validateBasemapUrlTemplate('https://tiles.example.test/{z}/{x}/{y}.png?bbox={bbox-epsg-3857}'),
    ).toBeNull()
  })

  it('names both valid forms when neither is present at all', () => {
    const message = validateBasemapUrlTemplate('https://tiles.example.test/karte.png')
    expect(message).toMatch(/\{z\}/)
    expect(message).toMatch(/\{bbox-epsg-3857\}/)
  })

  it('resolves a validated URL into its own basemap definition', () => {
    const url = 'https://tiles.example.test/{z}/{x}/{y}.png'
    expect(validateBasemapUrlTemplate(url)).toBeNull()

    const resolved = resolveBasemap(CATALOG, url)
    expect(resolved.id).toBe(url)
    expect(Object.values(resolved.sources)[0].tiles).toEqual([url])
    // A URL nobody in the catalog curated carries no licence claim.
    expect(resolved.attribution).toEqual([])
  })
})

describe('basemapChange', () => {
  it('reports nothing to persist when the stored id is already the chosen one', () => {
    expect(basemapChange(CATALOG, 'osm-dark', 'osm-dark')).toBeNull()
  })

  it('reports the chosen id when it differs', () => {
    expect(basemapChange(CATALOG, 'osm', 'none')).toBe('none')
    expect(basemapChange(CATALOG, null, 'opentopo')).toBe('opentopo')
  })

  it('normalises a stored id the catalog does not know', () => {
    expect(basemapChange(CATALOG, 'positron', 'osm')).toBe('osm')
  })

  it('never persists an id outside the catalog', () => {
    expect(basemapChange(CATALOG, 'osm', 'something-else')).toBeNull()
    expect(basemapChange(CATALOG, 'none', 'something-else')).toBe('osm')
  })

  it('persists a free-text URL verbatim, not as a normalised catalog id', () => {
    const url = 'https://tiles.example.test/{z}/{x}/{y}.png'
    expect(basemapChange(CATALOG, 'osm', url)).toBe(url)
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
    const style = buildBasemapStyle(CATALOG)
    expect(style.glyphs).toBe('http://localhost:5173/api/glyphs/{fontstack}/{range}.pbf')
    expect(style.glyphs).not.toMatch(/openmaptiles|maptiler|fonts\.google/i)
  })

  it('keeps the self-hosted glyphs for every basemap, including none', () => {
    for (const entry of CATALOG) {
      expect(buildBasemapStyle(CATALOG, entry.id).glyphs).toBe(
        'http://localhost:5173/api/glyphs/{fontstack}/{range}.pbf',
      )
    }
  })

  it('builds the style of the requested basemap', () => {
    const style = buildBasemapStyle(CATALOG, 'opentopo')
    expect(style.layers.map((layer) => layer.id)).toEqual(['basemap:opentopo'])
    expect(Object.keys(style.sources)).toEqual(['basemap:opentopo'])
  })

  it('builds an empty but valid style for "no basemap"', () => {
    const style = buildBasemapStyle(CATALOG, 'none')
    expect(style.layers).toEqual([])
    expect(style.sources).toEqual({})
    expect(style.version).toBe(8)
  })

  it('falls back to OSM instead of rendering nothing for an unknown id', () => {
    expect(buildBasemapStyle(CATALOG, 'stamen-toner').layers.map((layer) => layer.id)).toEqual([
      'basemap:osm',
    ])
  })

  it('defaults to full opacity', () => {
    const style = buildBasemapStyle(CATALOG, 'osm')
    expect(style.layers[0].paint).toMatchObject({ 'raster-opacity': 1 })
  })

  it('bakes a reduced opacity into the layer paint, on top of the variant paint', () => {
    const style = buildBasemapStyle(CATALOG, 'osm-dark', 0.4)
    expect(style.layers[0].paint).toMatchObject({
      'raster-opacity': 0.4,
      'raster-brightness-max': 0.38,
    })
  })

  it('carries no opacity paint for "no basemap", which has no layer to carry it on', () => {
    expect(buildBasemapStyle(CATALOG, 'none', 0.4).layers).toEqual([])
  })

  it('builds a style straight from a free-text URL', () => {
    const url = 'https://tiles.example.test/{z}/{x}/{y}.png'
    const style = buildBasemapStyle(CATALOG, url)
    expect(style.layers.map((layer) => layer.id)).toEqual([`basemap:${url}`])
  })
})
