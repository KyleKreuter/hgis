import { describe, expect, it } from 'vitest'
import type { LayerSummary } from '@/api/layers'
import { heatmapRangeTargets } from './heatmapFieldRanges'

function makeLayer(overrides: Partial<LayerSummary> = {}): LayerSummary {
  return {
    id: 'layer-1',
    name: 'Lärmpegel',
    geometryType: 'MULTIPOINT',
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

describe('heatmapRangeTargets', () => {
  it('nimmt einen Heatmap-Layer mit gewähltem Feld auf', () => {
    const layer = makeLayer({
      style: { version: 1, renderer: { type: 'heatmap', field: 'laut_wert', radius: 30, intensity: 1, ramp: 'blues' }, opacity: 1 },
    })

    expect(heatmapRangeTargets([layer])).toEqual([{ layerId: 'layer-1', field: 'laut_wert' }])
  })

  it('lässt eine Heatmap ohne Feld aus -- Dichte braucht keine Spanne', () => {
    const layer = makeLayer({
      style: { version: 1, renderer: { type: 'heatmap', field: null, radius: 30, intensity: 1, ramp: 'blues' }, opacity: 1 },
    })

    expect(heatmapRangeTargets([layer])).toEqual([])
  })

  it('lässt einen ungestylten Layer und jeden anderen Renderer aus', () => {
    const unstyled = makeLayer({ style: null })
    const single = makeLayer({
      id: 'layer-2',
      style: { version: 1, renderer: { type: 'single', symbol: { kind: 'marker', shape: 'circle', size: 3, fillColor: '#000', strokeColor: '#fff', strokeWidth: 1 } }, opacity: 1 },
    })

    expect(heatmapRangeTargets([unstyled, single])).toEqual([])
  })

  it('lässt ein Kartenbild aus, dessen style-Feld eine andere Form hat', () => {
    const mapImage = makeLayer({
      kind: 'WMS',
      geometryType: null,
      srid: null,
      wms: { serviceUrl: 'https://example.org/wms', layers: ['a'], imageFormat: 'image/png', legendUrl: null, queryable: false },
      style: { opacity: 0.8 },
    })

    expect(heatmapRangeTargets([mapImage])).toEqual([])
  })
})
