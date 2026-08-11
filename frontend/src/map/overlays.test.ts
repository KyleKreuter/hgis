import { describe, expect, it, vi } from 'vitest'
import type { LayerSpecification } from 'maplibre-gl'
import {
  isOverlayLayer,
  overlayOrder,
  overlayTier,
  raiseOverlays,
  type OverlayMapLike,
} from './overlays'

const DATA = 'hgis-layer-gebaeude-render'
const LABEL = 'hgis-layer-gebaeude-label'
const SELECTION = 'hgis-layer-gebaeude-selected'
const SELECTION_OUTLINE = 'hgis-layer-gebaeude-selected-outline'
const SKETCH_FILL = 'hgis-measurement-fill'
const SKETCH_VERTEX = 'hgis-measurement-vertex'

/**
 * Ein Stellvertreter für maplibregl.Map, der nur die Reihenfolge führt -- `moveLayer`
 * ohne beforeId schiebt wie in MapLibre ganz nach oben.
 */
function createFakeMap(initial: string[]) {
  const order = [...initial]

  const map: OverlayMapLike = {
    getStyle: vi.fn(() => ({
      layers: order.map((id) => ({ id })) as unknown as LayerSpecification[],
    })) as unknown as OverlayMapLike['getStyle'],
    getLayer: vi.fn((id: string) =>
      order.includes(id) ? ({ id } as unknown as ReturnType<OverlayMapLike['getLayer']>) : undefined,
    ) as unknown as OverlayMapLike['getLayer'],
    moveLayer: vi.fn((id: string) => {
      const index = order.indexOf(id)
      if (index >= 0) order.splice(index, 1)
      order.push(id)
    }) as unknown as OverlayMapLike['moveLayer'],
  }

  return { map, order }
}

describe('overlayTier', () => {
  it('erkennt Auswahl und Messung, aber keinen Datenlayer', () => {
    expect(overlayTier(SELECTION)).toBe(0)
    expect(overlayTier(SKETCH_FILL)).toBe(1)
    expect(overlayTier(DATA)).toBe(-1)
    expect(overlayTier('basemap:osm')).toBe(-1)
    expect(isOverlayLayer(LABEL)).toBe(false)
  })
})

describe('overlayOrder', () => {
  it('stellt die Messung über die Auswahl, unabhängig davon, wer zuerst da war', () => {
    expect(overlayOrder([SKETCH_FILL, SELECTION, DATA])).toEqual([SELECTION, SKETCH_FILL])
  })

  it('behält innerhalb einer Stufe die bestehende Reihenfolge', () => {
    expect(
      overlayOrder([SELECTION, SELECTION_OUTLINE, DATA, SKETCH_FILL, SKETCH_VERTEX]),
    ).toEqual([SELECTION, SELECTION_OUTLINE, SKETCH_FILL, SKETCH_VERTEX])
  })

  it('liefert für einen Stil ohne Overlays nichts zurück', () => {
    expect(overlayOrder([DATA, LABEL, 'basemap:osm'])).toEqual([])
  })
})

describe('raiseOverlays', () => {
  it('hebt Auswahl und Messung wieder über die Daten', () => {
    const { map, order } = createFakeMap([SELECTION, SKETCH_FILL, DATA, LABEL])

    raiseOverlays(map)

    expect(order).toEqual([DATA, LABEL, SELECTION, SKETCH_FILL])
  })

  it('rührt einen Stil ohne Overlays nicht an', () => {
    const { map, order } = createFakeMap([DATA, LABEL])

    raiseOverlays(map)

    expect(map.moveLayer).not.toHaveBeenCalled()
    expect(order).toEqual([DATA, LABEL])
  })
})
