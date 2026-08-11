import { describe, expect, it, vi } from 'vitest'
import type { AddLayerObject, LayerSpecification, SourceSpecification } from 'maplibre-gl'
import { applyBasemap, type BasemapMapLike } from './applyBasemap'
import { buildBasemapStyle, resolveBasemap } from './basemap'

const GLYPHS = 'http://localhost:5173/api/glyphs/{fontstack}/{range}.pbf'

/**
 * A minimal in-memory stand-in for maplibregl.Map, in the same spirit as the one in
 * `syncLayers.test.ts`. Layer order matters here -- the point of `applyBasemap` is
 * that the background lands *below* the data layers -- so the layer list is an array
 * and `addLayer`'s `beforeId` splices into it exactly like MapLibre does.
 */
function createFakeMap(initial: { sources?: Record<string, SourceSpecification>; layers?: LayerSpecification[] } = {}) {
  const sources: Record<string, SourceSpecification> = { ...initial.sources }
  const layers: LayerSpecification[] = [...(initial.layers ?? [])]

  const map: BasemapMapLike = {
    getStyle: vi.fn(() => ({
      version: 8,
      glyphs: GLYPHS,
      sources: { ...sources },
      layers: [...layers],
    })) as unknown as BasemapMapLike['getStyle'],
    addSource: vi.fn((id: string, source: SourceSpecification) => {
      sources[id] = source
    }) as unknown as BasemapMapLike['addSource'],
    removeSource: vi.fn((id: string) => {
      delete sources[id]
    }) as unknown as BasemapMapLike['removeSource'],
    getSource: vi.fn((id: string) => sources[id] as unknown as ReturnType<BasemapMapLike['getSource']>) as unknown as BasemapMapLike['getSource'],
    addLayer: vi.fn((layer: AddLayerObject, beforeId?: string) => {
      const index = beforeId ? layers.findIndex((existing) => existing.id === beforeId) : -1
      if (index >= 0) layers.splice(index, 0, layer as LayerSpecification)
      else layers.push(layer as LayerSpecification)
    }) as unknown as BasemapMapLike['addLayer'],
    removeLayer: vi.fn((id: string) => {
      const index = layers.findIndex((layer) => layer.id === id)
      if (index >= 0) layers.splice(index, 1)
    }) as unknown as BasemapMapLike['removeLayer'],
    getLayer: vi.fn((id: string) =>
      layers.find((layer) => layer.id === id) as unknown as ReturnType<BasemapMapLike['getLayer']>,
    ) as unknown as BasemapMapLike['getLayer'],
  }

  return { map, sources, layers }
}

/** A map showing OSM with one data layer on top, i.e. the normal state of a project. */
function createLoadedMap() {
  const style = buildBasemapStyle('osm')
  return createFakeMap({
    sources: {
      ...style.sources,
      'layer:gebaeude': { type: 'vector', tiles: ['https://example.test/{z}/{x}/{y}.pbf'] },
    },
    layers: [...style.layers, { id: 'layer:gebaeude-fill', type: 'fill', source: 'layer:gebaeude', 'source-layer': 'default' }],
  })
}

describe('applyBasemap', () => {
  it('replaces the basemap and leaves the data layers in place', () => {
    const { map, layers, sources } = createLoadedMap()

    expect(applyBasemap(map, resolveBasemap('opentopo'))).toBe(true)

    expect(layers.map((layer) => layer.id)).toEqual(['basemap:opentopo', 'layer:gebaeude-fill'])
    expect(sources['layer:gebaeude']).toBeDefined()
    expect(sources['basemap:osm']).toBeUndefined()
    expect(sources['basemap:opentopo']).toBeDefined()
  })

  it('keeps the new basemap below every foreign layer', () => {
    const { map, layers } = createLoadedMap()

    applyBasemap(map, resolveBasemap('osm-dark'))

    expect(layers[0].id).toBe('basemap:osm-dark')
    expect(layers.at(-1)?.id).toBe('layer:gebaeude-fill')
  })

  it('carries the variant paint properties onto the map', () => {
    const { map, layers } = createLoadedMap()

    applyBasemap(map, resolveBasemap('osm-dark'))

    expect(layers[0].paint).toMatchObject({ 'raster-brightness-max': 0.38 })
  })

  it('removes everything for "no basemap" without touching the data layers', () => {
    const { map, layers, sources } = createLoadedMap()

    expect(applyBasemap(map, resolveBasemap('none'))).toBe(true)

    expect(layers.map((layer) => layer.id)).toEqual(['layer:gebaeude-fill'])
    expect(Object.keys(sources)).toEqual(['layer:gebaeude'])
  })

  it('does nothing when the requested basemap is already applied', () => {
    const { map } = createLoadedMap()

    expect(applyBasemap(map, resolveBasemap('osm'))).toBe(false)
    expect(map.removeLayer).not.toHaveBeenCalled()
    expect(map.addLayer).not.toHaveBeenCalled()
    expect(map.removeSource).not.toHaveBeenCalled()
  })

  it('never rewrites the style, so the self-hosted glyph URL survives a swap', () => {
    const { map } = createLoadedMap()

    applyBasemap(map, resolveBasemap('none'))
    applyBasemap(map, resolveBasemap('osm-light'))

    expect(map.getStyle().glyphs).toBe(GLYPHS)
  })

  it('restores a basemap after "none" was applied', () => {
    const { map, layers } = createLoadedMap()

    applyBasemap(map, resolveBasemap('none'))
    expect(applyBasemap(map, resolveBasemap('osm-light'))).toBe(true)

    expect(layers.map((layer) => layer.id)).toEqual(['basemap:osm-light', 'layer:gebaeude-fill'])
  })

  it('falls back to OSM for an unknown stored id', () => {
    const { map, layers } = createFakeMap()

    applyBasemap(map, resolveBasemap('mapbox-satellite'))

    expect(layers.map((layer) => layer.id)).toEqual(['basemap:osm'])
  })
})
