import { describe, expect, it } from 'vitest'
import { buildTileUrl } from './layerSpecs'

describe('buildTileUrl', () => {
  it('hängt dataVersion, styleVersion, clipVersion und renderVersion als Version an', () => {
    const url = buildTileUrl({
      id: 'layer-1',
      dataVersion: 3,
      styleVersion: 2,
      clipVersion: 5,
      renderVersion: 1,
    })

    expect(url).toBe('/api/layers/layer-1/tiles/{z}/{x}/{y}.mvt?v=3.2.5.r1')
  })

  it('hängt clipVersion auch bei 0 an, damit ein Wechsel von/zu 0 die URL trotzdem ändert', () => {
    const url = buildTileUrl({
      id: 'layer-1',
      dataVersion: 1,
      styleVersion: 1,
      clipVersion: 0,
      renderVersion: 1,
    })

    expect(url).toBe('/api/layers/layer-1/tiles/{z}/{x}/{y}.mvt?v=1.1.0.r1')
  })

  it('ändert die URL, wenn nur clipVersion sich ändert -- der Auslöser für syncMapLayers', () => {
    const before = buildTileUrl({ id: 'layer-1', dataVersion: 1, styleVersion: 1, clipVersion: 0 })
    const after = buildTileUrl({ id: 'layer-1', dataVersion: 1, styleVersion: 1, clipVersion: 1 })

    expect(before).not.toBe(after)
  })

  /**
   * Der Zweck von renderVersion: Sie ist der einzige Teil der Adresse, der sich bewegt,
   * wenn der Server dieselben Daten anders darstellt als vorher. Ohne diesen Test wäre
   * ein versehentlich weggelassenes Feld in `buildTileUrl` von nichts zu merken -- die
   * URL sähe weiterhin gültig aus.
   */
  it('ändert die URL, wenn nur renderVersion sich ändert', () => {
    const layer = { id: 'layer-1', dataVersion: 1, styleVersion: 1, clipVersion: 0 }
    const before = buildTileUrl({ ...layer, renderVersion: 1 })
    const after = buildTileUrl({ ...layer, renderVersion: 2 })

    expect(before).not.toBe(after)
    expect(after).toMatch(/\.r2$/)
  })

  it('liest eine fehlende renderVersion als 1, den Wert ihrer Einführung', () => {
    const withoutField = buildTileUrl({ id: 'layer-1', dataVersion: 1, styleVersion: 1, clipVersion: 0 })
    const withOne = buildTileUrl({
      id: 'layer-1',
      dataVersion: 1,
      styleVersion: 1,
      clipVersion: 0,
      renderVersion: 1,
    })

    expect(withoutField).toBe(withOne)
  })
})
