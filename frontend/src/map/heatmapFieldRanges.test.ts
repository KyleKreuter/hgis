import { describe, expect, it } from 'vitest'
import type { LayerSummary } from '@/api/layers'
import type { FieldRangeState } from '@/styling/classification'
import { heatmapRangeTargets, layersEnteringError, rangeToastMessage } from './heatmapFieldRanges'

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

  /**
   * `'invalid'` (eine erfolgreich beantwortete, aber unbrauchbare Anfrage) meldet sich
   * genauso wie `'error'` -- beide sind "bestätigt nicht verfügbar", nur die Formulierung
   * unterscheidet sich (siehe `rangeToastMessage` unten).
   */
  it('meldet auch einen Wechsel nach invalid', () => {
    const states = [{ layerId: 'layer-1', state: 'invalid' as const }]

    expect(layersEnteringError(states, new Set())).toEqual(['layer-1'])
  })
})

describe('rangeToastMessage', () => {
  const layer = { field: 'laenge_km', layerName: 'Fluglärm Hamburg' }

  it('rät bei einer fehlgeschlagenen Anfrage zu Verbindung oder Neuladen', () => {
    const message = rangeToastMessage({ ...layer, state: 'error' })

    expect(message).toContain('nicht laden')
    expect(message).toContain('Verbindung')
    expect(message).toContain('Seite neu')
  })

  /**
   * Der Fund aus der Teamrunde: für "invalid" ist "laden Sie die Seite neu" wirkungslose
   * Beratung -- dieselbe Anfrage mit demselben Schlüssel liefert nach dem Neuladen exakt
   * dasselbe. Die Meldung darf diesen Rat deshalb nicht enthalten, und muss stattdessen
   * auf die Daten selbst verweisen.
   */
  it('rät bei einer erfolgreichen, aber unbrauchbaren Antwort nicht zum Neuladen', () => {
    const message = rangeToastMessage({ ...layer, state: 'invalid' })

    expect(message).not.toContain('Seite neu')
    expect(message).not.toContain('Verbindung')
    expect(message).toContain('Werte')
  })

  it('nennt Feld und Layer in beiden Fällen beim Namen', () => {
    expect(rangeToastMessage({ ...layer, state: 'error' })).toContain('laenge_km')
    expect(rangeToastMessage({ ...layer, state: 'error' })).toContain('Fluglärm Hamburg')
    expect(rangeToastMessage({ ...layer, state: 'invalid' })).toContain('laenge_km')
    expect(rangeToastMessage({ ...layer, state: 'invalid' })).toContain('Fluglärm Hamburg')
  })
})
