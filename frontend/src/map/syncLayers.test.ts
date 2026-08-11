import { describe, expect, it, vi } from 'vitest'
import type { AddLayerObject, VectorSourceSpecification } from 'maplibre-gl'
import type { LayerSummary } from '@/api/layers'
import type { LayerStyle } from '@/styling/types'
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
    // Only the layer order is modelled -- that is what `raiseOverlays` reads.
    getStyle: vi.fn(() => ({
      layers: layerOrder.map((id) => ({ id })),
    })) as unknown as MapLike['getStyle'],
    moveLayer: vi.fn((id: string) => {
      const index = layerOrder.indexOf(id)
      if (index >= 0) layerOrder.splice(index, 1)
      layerOrder.push(id)
    }) as unknown as MapLike['moveLayer'],
    setLayoutProperty: vi.fn((id: string, name: string, value: unknown) => {
      const layer = layers.get(id) as (AddLayerObject & { layout?: Record<string, unknown> }) | undefined
      if (layer) layer.layout = { ...layer.layout, [name]: value }
    }) as unknown as MapLike['setLayoutProperty'],
    setPaintProperty: vi.fn((id: string, name: string, value: unknown) => {
      const layer = layers.get(id) as (AddLayerObject & { paint?: Record<string, unknown> }) | undefined
      if (layer) layer.paint = { ...layer.paint, [name]: value }
    }) as unknown as MapLike['setPaintProperty'],
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

  /**
   * Die Regression, um die es geht: `syncMapLayers` schiebt am Ende jeden Datenlayer
   * nach ganz oben. Vor der gemeinsamen Overlay-Regel (`overlays`) lag danach die
   * laufende Messung unter den Daten -- ausgelöst von etwas so Beiläufigem wie einem
   * Sichtbarkeits-Haken im Layerbaum.
   */
  it('lässt Messung und Auswahl auch nach einem Reorder oben', () => {
    const { map, layerOrder } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    const first = makeLayer({ id: 'a', zIndex: 0 })
    syncMapLayers(map, [first], applied)

    // So legen SelectionHighlight und MeasurementLayer ihre Layer an: obenauf.
    map.addLayer({ id: 'hgis-layer-a-selected', type: 'fill', source: 's' } as AddLayerObject)
    map.addLayer({ id: 'hgis-measurement-line', type: 'line', source: 'm' } as AddLayerObject)

    syncMapLayers(map, [first, makeLayer({ id: 'b', zIndex: 1 })], applied)

    expect(layerOrder).toEqual([
      'hgis-layer-a-render',
      'hgis-layer-b-render',
      'hgis-layer-a-selected',
      'hgis-measurement-line',
    ])
  })

  it('sortiert eine später hinzugekommene Auswahl unter die Messung', () => {
    const { map, layerOrder } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    syncMapLayers(map, [makeLayer({ id: 'a' })], applied)

    map.addLayer({ id: 'hgis-measurement-line', type: 'line', source: 'm' } as AddLayerObject)
    map.addLayer({ id: 'hgis-layer-a-selected', type: 'fill', source: 's' } as AddLayerObject)

    syncMapLayers(map, [makeLayer({ id: 'a' })], applied)

    expect(layerOrder).toEqual([
      'hgis-layer-a-render',
      'hgis-layer-a-selected',
      'hgis-measurement-line',
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

function styleWithColor(color: string): LayerStyle {
  return {
    version: 1,
    renderer: {
      type: 'single',
      symbol: { kind: 'fill', fillColor: color, fillOpacity: 0.25, outlineColor: '#262626', outlineWidth: 1 },
    },
    opacity: 1,
  }
}

const LABELS = {
  enabled: true,
  field: 'name',
  size: 12,
  color: '#262626',
  haloColor: '#ffffff',
  haloWidth: 1.5,
  minZoom: 12,
  allowOverlap: false,
}

describe('syncMapLayers mit Symbologie', () => {
  /**
   * The whole point of the server keeping `styleVersion` unchanged for a colour change:
   * the tiles stay valid, so the map must not touch the source. A layer that is torn
   * down and rebuilt would be just as tile-free but re-runs layout over every loaded
   * tile -- enough to make a colour slider stutter.
   */
  it('wendet eine Farbänderung über setPaintProperty an, ohne Source oder Layer anzufassen', () => {
    const { map } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    syncMapLayers(map, [makeLayer({ style: styleWithColor('#404040') })], applied)
    const addLayerCalls = (map.addLayer as ReturnType<typeof vi.fn>).mock.calls.length

    syncMapLayers(map, [makeLayer({ style: styleWithColor('#0072b2') })], applied)

    expect(map.setPaintProperty).toHaveBeenCalledWith('hgis-layer-layer-1-render', 'fill-color', '#0072b2')
    expect((map.addLayer as ReturnType<typeof vi.fn>).mock.calls.length).toBe(addLayerCalls)
    expect(map.removeLayer).not.toHaveBeenCalled()
    expect(map.removeSource).not.toHaveBeenCalled()
  })

  it('baut die Layer neu auf, wenn Beschriftung hinzukommt -- die Source bleibt stehen', () => {
    const { map, layers } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    const style = styleWithColor('#404040')
    syncMapLayers(map, [makeLayer({ style })], applied)

    syncMapLayers(map, [makeLayer({ style: { ...style, labels: LABELS } })], applied)

    expect([...layers.keys()]).toEqual(['hgis-layer-layer-1-render', 'hgis-layer-layer-1-label'])
    expect(map.removeSource).not.toHaveBeenCalled()
    expect(map.addSource).toHaveBeenCalledTimes(1)
  })

  it('legt den Label-Layer über die Geometrie und hält die Reihenfolge zwischen Layern', () => {
    const { map, layerOrder } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    const labelled = makeLayer({ id: 'a', zIndex: 0, style: { ...styleWithColor('#404040'), labels: LABELS } })
    const above = makeLayer({ id: 'b', zIndex: 1 })

    syncMapLayers(map, [labelled, above], applied)

    expect(layerOrder).toEqual([
      'hgis-layer-a-render',
      'hgis-layer-a-label',
      'hgis-layer-b-render',
    ])
  })

  it('entfernt auch den Label-Layer, wenn der Layer aus dem Projekt fällt', () => {
    const { map, layers, sources } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    syncMapLayers(map, [makeLayer({ style: { ...styleWithColor('#404040'), labels: LABELS } })], applied)

    syncMapLayers(map, [], applied)

    expect(layers.size).toBe(0)
    expect(sources.size).toBe(0)
  })

  /**
   * A PATCH answers with the server's own serialisation, a later GET reads the same
   * document back out of a jsonb column, and PostgreSQL reorders the keys in there.
   * Semantically the same style, textually a different one -- and a refetch must not
   * turn that into paint updates on every layer of the project.
   */
  it('erkennt einen Style mit vertauschter Schlüsselreihenfolge als unverändert', () => {
    const { map } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    const style = styleWithColor('#0072b2')
    syncMapLayers(map, [makeLayer({ style })], applied)

    const reordered = {
      opacity: style.opacity,
      renderer: {
        symbol: {
          outlineWidth: 1,
          fillColor: '#0072b2',
          outlineColor: '#262626',
          kind: 'fill',
          fillOpacity: 0.25,
        },
        type: 'single',
      },
      version: 1,
    } as unknown as LayerStyle
    syncMapLayers(map, [makeLayer({ style: reordered })], applied)

    expect(map.setPaintProperty).not.toHaveBeenCalled()
    expect(map.setLayoutProperty).not.toHaveBeenCalled()
    expect(map.addLayer).toHaveBeenCalledTimes(1)
  })

  it('geht beim Wechsel auf kategorisiert in einen Ausdruck über, ohne Kacheln neu zu holen', () => {
    const { map } = createFakeMap()
    const applied = new Map<string, AppliedLayer>()
    syncMapLayers(map, [makeLayer({ style: styleWithColor('#404040') })], applied)

    const categorized: LayerStyle = {
      version: 1,
      renderer: {
        type: 'categorized',
        field: 'art',
        categories: [
          {
            value: 'A',
            label: 'A',
            symbol: { kind: 'fill', fillColor: '#0072b2', fillOpacity: 0.25, outlineColor: '#262626', outlineWidth: 1 },
          },
        ],
        fallbackSymbol: { kind: 'fill', fillColor: '#a3a3a3', fillOpacity: 0.25, outlineColor: '#262626', outlineWidth: 1 },
      },
      opacity: 1,
    }
    syncMapLayers(map, [makeLayer({ style: categorized })], applied)

    expect(map.setPaintProperty).toHaveBeenCalledWith('hgis-layer-layer-1-render', 'fill-color', [
      'match',
      ['get', 'art'],
      'A',
      '#0072b2',
      '#a3a3a3',
    ])
    expect(map.removeSource).not.toHaveBeenCalled()
  })
})
