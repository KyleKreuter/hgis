import { describe, expect, it, vi } from 'vitest'
import type { AddLayerObject, VectorSourceSpecification } from 'maplibre-gl'
import type { LayerSummary } from '@/api/layers'
import { type AppliedLayer, type MapLike, syncMapLayers } from './syncLayers'

interface FakeSource {
  spec: VectorSourceSpecification
  setTiles?: ReturnType<typeof vi.fn>
}

/**
 * A minimal in-memory stand-in for maplibregl.Map. `layerOrder` records the
 * current bottom-to-top stack so the reorder tests can assert on it directly,
 * mirroring how MapLibre's own `moveLayer(id)` (no beforeId) moves a layer to
 * the very top of the style's layer array.
 */
function createFakeMap(options: { sourceSupportsSetTiles?: boolean } = {}) {
  const { sourceSupportsSetTiles = true } = options
  const sources = new Map<string, FakeSource>()
  const layers = new Map<string, AddLayerObject>()
  const layerOrder: string[] = []

  const map: MapLike = {
    addSource: vi.fn((id: string, spec: VectorSourceSpecification) => {
      sources.set(id, { spec, setTiles: sourceSupportsSetTiles ? vi.fn() : undefined })
    }) as unknown as MapLike['addSource'],
    removeSource: vi.fn((id: string) => {
      sources.delete(id)
    }) as unknown as MapLike['removeSource'],
    getSource: vi.fn((id: string) => {
      const source = sources.get(id)
      if (!source) return undefined
      return { setTiles: source.setTiles } as unknown as ReturnType<MapLike['getSource']>
    }) as unknown as MapLike['getSource'],
    addLayer: vi.fn((layer: AddLayerObject) => {
      layers.set(layer.id, layer)
      layerOrder.push(layer.id)
    }) as unknown as MapLike['addLayer'],
    removeLayer: vi.fn((id: string) => {
      layers.delete(id)
      const index = layerOrder.indexOf(id)
      if (index >= 0) layerOrder.splice(index, 1)
    }) as unknown as MapLike['removeLayer'],
    getLayer: vi.fn((id: string) => layers.get(id) as unknown as ReturnType<MapLike['getLayer']>) as unknown as MapLike['getLayer'],
    moveLayer: vi.fn((id: string) => {
      const index = layerOrder.indexOf(id)
      if (index >= 0) layerOrder.splice(index, 1)
      layerOrder.push(id)
    }) as unknown as MapLike['moveLayer'],
    setLayoutProperty: vi.fn((id: string, name: string, value: unknown) => {
      const layer = layers.get(id) as (AddLayerObject & { layout?: Record<string, unknown> }) | undefined
      if (layer) layer.layout = { ...layer.layout, [name]: value }
    }) as unknown as MapLike['setLayoutProperty'],
  }

  return { map, sources, layers, layerOrder }
}

function makeLayer(overrides: Partial<LayerSummary> = {}): LayerSummary {
  return {
    id: 'layer-1',
    name: 'Gebäude',
    geometryType: 'MULTIPOLYGON',
    srid: 25832,
    featureCount: 100,
    visible: true,
    zIndex: 0,
    minZoom: 0,
    maxZoom: 22,
    dataVersion: 1,
    styleVersion: 1,
    extent: null,
    ...overrides,
  }
}

describe('syncMapLayers', () => {
  it('legt Source und einen Render-Layer für eine neue MULTIPOLYGON-Schicht an', () => {
    const { map, sources, layers } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    const layer = makeLayer()

    syncMapLayers(map, [layer], applied)

    expect(sources.has('hgis-layer-layer-1')).toBe(true)
    expect(sources.get('hgis-layer-layer-1')?.spec.tiles).toEqual([
      '/api/layers/layer-1/tiles/{z}/{x}/{y}.mvt?v=1.1',
    ])
    expect([...layers.keys()]).toEqual(['hgis-layer-layer-1-render'])
    expect(layers.get('hgis-layer-layer-1-render')?.type).toBe('fill')
    expect(applied.get('layer-1')).toMatchObject({ tileUrl: '/api/layers/layer-1/tiles/{z}/{x}/{y}.mvt?v=1.1' })
  })

  it('legt für einen GEOMETRY-Layer drei nach geometry-type gefilterte Sublayer an', () => {
    const { map, layers } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    const layer = makeLayer({ geometryType: 'GEOMETRY' })

    syncMapLayers(map, [layer], applied)

    expect([...layers.keys()]).toEqual([
      'hgis-layer-layer-1-polygon',
      'hgis-layer-layer-1-line',
      'hgis-layer-layer-1-point',
    ])
    expect(layers.get('hgis-layer-layer-1-polygon')?.type).toBe('fill')
    expect(layers.get('hgis-layer-layer-1-line')?.type).toBe('line')
    expect(layers.get('hgis-layer-layer-1-point')?.type).toBe('circle')
    expect((layers.get('hgis-layer-layer-1-point') as AddLayerObject & { filter: unknown }).filter).toEqual([
      '==',
      ['geometry-type'],
      'Point',
    ])
  })

  it('entfernt Source und Layer, sobald ein Layer nicht mehr im Store ist', () => {
    const { map, sources, layers } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    const layer = makeLayer()

    syncMapLayers(map, [layer], applied)
    expect(sources.size).toBe(1)

    syncMapLayers(map, [], applied)

    expect(sources.size).toBe(0)
    expect(layers.size).toBe(0)
    expect(applied.has('layer-1')).toBe(false)
  })

  it('lädt Kacheln per setTiles neu, wenn sich dataVersion ändert und setTiles verfügbar ist', () => {
    const { map, sources } = createFakeMap({ sourceSupportsSetTiles: true })
    const applied = new Map<string, AppliedLayer>()
    const layer = makeLayer({ dataVersion: 1 })
    syncMapLayers(map, [layer], applied)

    const setTilesSpy = sources.get('hgis-layer-layer-1')!.setTiles!
    const addSourceCallsBefore = (map.addSource as ReturnType<typeof vi.fn>).mock.calls.length

    syncMapLayers(map, [makeLayer({ dataVersion: 2 })], applied)

    expect(setTilesSpy).toHaveBeenCalledWith(['/api/layers/layer-1/tiles/{z}/{x}/{y}.mvt?v=2.1'])
    expect((map.addSource as ReturnType<typeof vi.fn>).mock.calls.length).toBe(addSourceCallsBefore)
    expect(applied.get('layer-1')?.tileUrl).toBe('/api/layers/layer-1/tiles/{z}/{x}/{y}.mvt?v=2.1')
  })

  it('fällt auf entfernen+neu anlegen zurück, wenn die Source kein setTiles unterstützt', () => {
    const { map, sources } = createFakeMap({ sourceSupportsSetTiles: false })
    const applied = new Map<string, AppliedLayer>()
    syncMapLayers(map, [makeLayer({ dataVersion: 1 })], applied)

    syncMapLayers(map, [makeLayer({ dataVersion: 2 })], applied)

    expect(map.removeSource).toHaveBeenCalledWith('hgis-layer-layer-1')
    expect(sources.get('hgis-layer-layer-1')?.spec.tiles).toEqual([
      '/api/layers/layer-1/tiles/{z}/{x}/{y}.mvt?v=2.1',
    ])
  })

  it('legt Source neu an, wenn sich minZoom/maxZoom ändern', () => {
    const { map } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    syncMapLayers(map, [makeLayer({ minZoom: 0, maxZoom: 22 })], applied)

    syncMapLayers(map, [makeLayer({ minZoom: 5, maxZoom: 15 })], applied)

    expect(map.removeSource).toHaveBeenCalledWith('hgis-layer-layer-1')
    expect(map.addSource).toHaveBeenCalledTimes(2)
  })

  it('wechselt Sichtbarkeit über setLayoutProperty, ohne die Source neu zu laden', () => {
    const { map } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    syncMapLayers(map, [makeLayer({ visible: true })], applied)

    syncMapLayers(map, [makeLayer({ visible: false })], applied)

    expect(map.setLayoutProperty).toHaveBeenCalledWith('hgis-layer-layer-1-render', 'visibility', 'none')
    expect(map.removeSource).not.toHaveBeenCalled()
    expect(map.addSource).toHaveBeenCalledTimes(1)
  })

  it('ordnet Layer aufsteigend nach zIndex an, GEOMETRY-Sublayer bleiben polygon -> line -> point', () => {
    const { map, layerOrder } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    const bottom = makeLayer({ id: 'a', zIndex: 2 })
    const middle = makeLayer({ id: 'b', zIndex: 0, geometryType: 'GEOMETRY' })
    const top = makeLayer({ id: 'c', zIndex: 1 })

    syncMapLayers(map, [bottom, middle, top], applied)

    expect(layerOrder).toEqual([
      'hgis-layer-b-polygon',
      'hgis-layer-b-line',
      'hgis-layer-b-point',
      'hgis-layer-c-render',
      'hgis-layer-a-render',
    ])
  })

  it('startet ohne Layer sauber -- keine Source-/Layer-Aufrufe', () => {
    const { map } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()

    syncMapLayers(map, [], applied)

    expect(map.addSource).not.toHaveBeenCalled()
    expect(map.addLayer).not.toHaveBeenCalled()
    expect(applied.size).toBe(0)
  })
})
