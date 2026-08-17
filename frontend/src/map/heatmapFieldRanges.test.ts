import { describe, expect, it } from 'vitest'
import type { LayerSummary } from '@/api/layers'
import type { FieldRangeState } from '@/styling/classification'
import { heatmapRangeTargets, layersEnteringError, resolveRangeState } from './heatmapFieldRanges'

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

    expect(heatmapRangeTargets([layer])).toEqual([{ layerId: 'layer-1', layerName: 'Lärmpegel', field: 'laut_wert' }])
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

describe('resolveRangeState', () => {
  it('liest eine noch nicht angekommene Antwort als "lädt noch"', () => {
    expect(resolveRangeState(undefined)).toBeUndefined()
    expect(resolveRangeState({ isError: false, data: undefined })).toBeUndefined()
  })

  it('liest eine fehlgeschlagene Anfrage als "error"', () => {
    expect(resolveRangeState({ isError: true, data: undefined })).toBe('error')
  })

  it('liest eine echte, auch entartete Spanne als aufgelöst', () => {
    expect(resolveRangeState({ isError: false, data: { min: 0, max: 70 } })).toEqual({ min: 0, max: 70 })
    // Zwei Objekte mit demselben Wert sind eine korrekte Aussage über die Daten, keine
    // Störung -- das bleibt eine aufgelöste Spanne, nicht "error".
    expect(resolveRangeState({ isError: false, data: { min: 5, max: 5 } })).toEqual({ min: 5, max: 5 })
  })

  /**
   * Der Fund aus der Teamrunde: ein Layer ohne Objekte antwortet vermutlich mit
   * nicht-endlichen Grenzen statt mit einem HTTP-Fehler. `range.max > range.min` allein
   * läse `NaN > NaN` als `false` und würde das lautlos in denselben Topf wie "lädt noch"
   * werfen -- das ist genau der Fall, den `styleToMapLibre`'s Diagnose-Verlauf braucht.
   */
  it('liest eine erfolgreich aufgelöste, aber nicht-endliche Spanne als "error"', () => {
    expect(resolveRangeState({ isError: false, data: { min: NaN, max: NaN } })).toBe('error')
    expect(resolveRangeState({ isError: false, data: { min: 0, max: Infinity } })).toBe('error')
  })
})

describe('layersEnteringError', () => {
  it('meldet einen Layer, der neu in error wechselt', () => {
    const states = [{ layerId: 'layer-1', state: 'error' as const }]

    expect(layersEnteringError(states, new Set())).toEqual(['layer-1'])
  })

  it('meldet einen bereits bekannten Fehler kein zweites Mal', () => {
    const states = [{ layerId: 'layer-1', state: 'error' as const }]

    expect(layersEnteringError(states, new Set(['layer-1']))).toEqual([])
  })

  it('meldet nichts für lädt-noch oder eine aufgelöste Spanne', () => {
    const states: { layerId: string; state: FieldRangeState }[] = [
      { layerId: 'layer-1', state: undefined },
      { layerId: 'layer-2', state: { min: 0, max: 1 } },
    ]

    expect(layersEnteringError(states, new Set())).toEqual([])
  })

  it('meldet eine Erholung und einen erneuten Fehler beide wieder', () => {
    const failing = [{ layerId: 'layer-1', state: 'error' as const }]
    const recovered: { layerId: string; state: FieldRangeState }[] = [{ layerId: 'layer-1', state: { min: 0, max: 1 } }]

    // So, wie useHeatmapRangeErrorToasts previousErrorsRef nach jedem Lauf fortschreibt:
    // nach der Erholung ist layer-1 nicht mehr "bekannt fehlgeschlagen".
    const afterRecovery = new Set(recovered.filter((entry) => entry.state === 'error').map((entry) => entry.layerId))
    expect(afterRecovery.size).toBe(0)
    expect(layersEnteringError(failing, afterRecovery)).toEqual(['layer-1'])
  })
})
