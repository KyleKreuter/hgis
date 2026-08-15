import { describe, expect, it, vi } from 'vitest'
import type { LayerSpecification } from 'maplibre-gl'
import {
  isOverlayLayer,
  overlayOrder,
  overlayTier,
  raiseOverlays,
  PLACE_MARKER_LAYER_ID,
  type OverlayMapLike,
} from './overlays'

const DATA = 'hgis-layer-gebaeude-render'
const LABEL = 'hgis-layer-gebaeude-label'
const SELECTION = 'hgis-layer-gebaeude-selected'
const SELECTION_OUTLINE = 'hgis-layer-gebaeude-selected-outline'
const PLACE_MARKER = PLACE_MARKER_LAYER_ID
const SKETCH_FILL = 'hgis-measurement-fill'
const SKETCH_VERTEX = 'hgis-measurement-vertex'
const RECT_FILL = 'hgis-rectangle-select-fill'
const SPLIT_LINE = 'hgis-split-line-line'

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
  it('erkennt Auswahl, Ortsmarke, Messung und Rechteckauswahl, aber keinen Datenlayer', () => {
    expect(overlayTier(SELECTION)).toBe(0)
    expect(overlayTier(PLACE_MARKER)).toBe(1)
    expect(overlayTier(SKETCH_FILL)).toBe(2)
    expect(overlayTier(RECT_FILL)).toBe(3)
    expect(overlayTier(SPLIT_LINE)).toBe(4)
    expect(overlayTier(DATA)).toBe(-1)
    expect(overlayTier('basemap:osm')).toBe(-1)
    expect(isOverlayLayer(LABEL)).toBe(false)
  })
})

describe('overlayOrder', () => {
  it('stellt die Messung über die Auswahl, unabhängig davon, wer zuerst da war', () => {
    expect(overlayOrder([SKETCH_FILL, SELECTION, DATA])).toEqual([SELECTION, SKETCH_FILL])
  })

  it('stellt die Ortsmarke über die Auswahl, aber unter die Messung', () => {
    expect(overlayOrder([SKETCH_FILL, PLACE_MARKER, SELECTION, DATA])).toEqual([
      SELECTION,
      PLACE_MARKER,
      SKETCH_FILL,
    ])
  })

  it('stellt die Rechteckauswahl über Auswahl und Messung', () => {
    expect(overlayOrder([RECT_FILL, SKETCH_FILL, SELECTION, DATA])).toEqual([
      SELECTION,
      SKETCH_FILL,
      RECT_FILL,
    ])
  })

  it('stellt die Teilen-Linie über die Auswahlhervorhebung', () => {
    // Der einzige Overlay, der die Hervorhebung sicher überdeckt: die Linie wird über
    // ein Objekt gezogen, das gleichzeitig ausgewählt ist -- die Auswahl hat ja gesagt,
    // welches Objekt geteilt wird.
    expect(overlayOrder([SELECTION, SPLIT_LINE, DATA])).toEqual([SELECTION, SPLIT_LINE])
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
