import { describe, expect, it } from 'vitest'
import { buildTileUrl } from './layerSpecs'

describe('buildTileUrl', () => {
  it('hängt dataVersion, styleVersion und clipVersion als Punkt-getrennte Version an', () => {
    const url = buildTileUrl({ id: 'layer-1', dataVersion: 3, styleVersion: 2, clipVersion: 5 })

    expect(url).toBe('/api/layers/layer-1/tiles/{z}/{x}/{y}.mvt?v=3.2.5')
  })

  it('hängt clipVersion auch bei 0 an, damit ein Wechsel von/zu 0 die URL trotzdem ändert', () => {
    const url = buildTileUrl({ id: 'layer-1', dataVersion: 1, styleVersion: 1, clipVersion: 0 })

    expect(url).toBe('/api/layers/layer-1/tiles/{z}/{x}/{y}.mvt?v=1.1.0')
  })

  it('ändert die URL, wenn nur clipVersion sich ändert -- der Auslöser für syncMapLayers', () => {
    const before = buildTileUrl({ id: 'layer-1', dataVersion: 1, styleVersion: 1, clipVersion: 0 })
    const after = buildTileUrl({ id: 'layer-1', dataVersion: 1, styleVersion: 1, clipVersion: 1 })

    expect(before).not.toBe(after)
  })
})
