import { describe, expect, it } from 'vitest'
import type { MapImageLayerSummary } from '@/api/layers'
import { buildWmsGetMapUrl, wmsLayerSpec, wmsSourceSpec, WMS_TILE_SIZE } from './wmsTiles'

describe('buildWmsGetMapUrl', () => {
  it('baut genau die gemessene, funktionierende Anfrage (wms-api-vertrag.md)', () => {
    const url = buildWmsGetMapUrl({
      serviceUrl: 'https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan',
      layers: ['stadtplan'],
      imageFormat: 'image/png',
    })

    expect(url).toBe(
      'https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap' +
        '&LAYERS=stadtplan&STYLES=&CRS=EPSG:3857&BBOX={bbox-epsg-3857}' +
        '&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE',
    )
  })

  it('reiht mehrere Layer durch Komma getrennt, unten zuerst', () => {
    const url = buildWmsGetMapUrl({
      serviceUrl: 'https://example.org/wms',
      layers: ['grundriss', 'gebaeude', 'beschriftung'],
      imageFormat: 'image/png',
    })

    expect(url).toContain('LAYERS=grundriss,gebaeude,beschriftung')
  })

  it('übernimmt das Bildformat unverändert, auch mit Schrägstrich', () => {
    const url = buildWmsGetMapUrl({
      serviceUrl: 'https://example.org/wms',
      layers: ['a'],
      imageFormat: 'image/jpeg',
    })

    expect(url).toContain('FORMAT=image/jpeg')
  })
})

function makeWmsLayer(overrides: Partial<MapImageLayerSummary> = {}): MapImageLayerSummary {
  return {
    id: 'layer-1',
    name: 'Stadtplan',
    kind: 'WMS',
    geometryType: null,
    srid: null,
    featureCount: 0,
    visible: true,
    zIndex: 0,
    minZoom: 0,
    maxZoom: 22,
    dataVersion: 1,
    styleVersion: 1,
    extent: null,
    wms: {
      serviceUrl: 'https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan',
      layers: ['stadtplan'],
      imageFormat: 'image/png',
      legendUrl: null,
      queryable: true,
    },
    ...overrides,
  }
}

describe('wmsSourceSpec', () => {
  it('deklariert die Kachelgröße, die auch in der Anfrage steht', () => {
    const source = wmsSourceSpec(makeWmsLayer(), 'https://example.org/wms?…')
    expect(source.tileSize).toBe(WMS_TILE_SIZE)
  })

  it('übernimmt das Zoomfenster des Layers wie eine Vektorquelle', () => {
    const source = wmsSourceSpec(makeWmsLayer({ minZoom: 5, maxZoom: 18 }), 'https://example.org/wms?…')
    expect(source.minzoom).toBe(5)
    expect(source.maxzoom).toBe(18)
  })
})

describe('wmsLayerSpec', () => {
  it('zeichnet als raster mit raster-opacity, nicht fill-opacity', () => {
    const spec = wmsLayerSpec(makeWmsLayer(), 'hgis-layer-layer-1')
    expect(spec.type).toBe('raster')
    expect(spec.paint).toEqual({ 'raster-opacity': 1 })
  })

  it('blendet aus, wenn der Layer unsichtbar ist', () => {
    const spec = wmsLayerSpec(makeWmsLayer({ visible: false }), 'hgis-layer-layer-1')
    expect(spec.layout).toEqual({ visibility: 'none' })
  })

  it('liest eine künftige Deckkraft aus style.opacity, ohne dass eine da sein muss', () => {
    const spec = wmsLayerSpec(makeWmsLayer({ style: { version: 1, opacity: 0.5 } as never }), 'hgis-layer-layer-1')
    expect(spec.paint).toEqual({ 'raster-opacity': 0.5 })
  })
})
