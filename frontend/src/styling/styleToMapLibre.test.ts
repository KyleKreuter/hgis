import { createExpression } from '@maplibre/maplibre-gl-style-spec'
import { describe, expect, it } from 'vitest'
import type {
  CircleLayerSpecification,
  FillLayerSpecification,
  HeatmapLayerSpecification,
  LineLayerSpecification,
  SymbolLayerSpecification,
} from 'maplibre-gl'
import type { GeometryType, VectorLayerSummary } from '@/api/layers'
import { CIRCLE_PAINT, FILL_PAINT, LINE_PAINT } from '@/map/layerSpecs'
import type { FieldRangeState } from './classification'
import { defaultStyleFor } from './defaults'
import { styleToMapLibre } from './styleToMapLibre'
import type { LayerStyle } from './types'

const SOURCE_ID = 'hgis-layer-layer-1'

function makeLayer(overrides: Partial<VectorLayerSummary> = {}): VectorLayerSummary {
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

const GEOMETRY_TYPES: GeometryType[] = ['MULTIPOINT', 'MULTILINESTRING', 'MULTIPOLYGON', 'GEOMETRY']

describe('styleToMapLibre ohne Style', () => {
  it('erzeugt exakt die monochrome Standarddarstellung', () => {
    const point = styleToMapLibre(null, makeLayer({ geometryType: 'MULTIPOINT' }), SOURCE_ID)
    const line = styleToMapLibre(null, makeLayer({ geometryType: 'MULTILINESTRING' }), SOURCE_ID)
    const polygon = styleToMapLibre(null, makeLayer({ geometryType: 'MULTIPOLYGON' }), SOURCE_ID)

    expect((point[0] as CircleLayerSpecification).paint).toEqual(CIRCLE_PAINT)
    expect((line[0] as LineLayerSpecification).paint).toEqual(LINE_PAINT)
    expect((polygon[0] as FillLayerSpecification).paint).toEqual(FILL_PAINT)
  })

  it('legt keinen Label-Layer an', () => {
    for (const geometryType of GEOMETRY_TYPES) {
      const specs = styleToMapLibre(null, makeLayer({ geometryType }), SOURCE_ID)
      expect(specs.some((spec) => spec.id.endsWith('-label'))).toBe(false)
    }
  })

  /**
   * The symbology panel opens on `defaultStyleFor` when a layer has never been styled.
   * If that style rendered differently the map would jump the moment the panel opens --
   * and nobody would connect the jump to having only looked.
   */
  it('ist von der Standard-Style-Vorlage nicht zu unterscheiden', () => {
    for (const geometryType of GEOMETRY_TYPES) {
      const layer = makeLayer({ geometryType })
      expect(styleToMapLibre(defaultStyleFor(geometryType), layer, SOURCE_ID)).toEqual(
        styleToMapLibre(null, layer, SOURCE_ID),
      )
    }
  })
})

describe('styleToMapLibre je Renderer', () => {
  it('single', () => {
    const style: LayerStyle = {
      version: 1,
      renderer: {
        type: 'single',
        symbol: {
          kind: 'fill',
          fillColor: '#27ae60',
          fillOpacity: 0.5,
          outlineColor: '#1e8449',
          outlineWidth: 1,
        },
      },
      opacity: 0.8,
    }

    expect(styleToMapLibre(style, makeLayer(), SOURCE_ID)).toMatchSnapshot()
  })

  it('categorized', () => {
    const style: LayerStyle = {
      version: 1,
      renderer: {
        type: 'categorized',
        field: 'nutzungsart',
        categories: [
          {
            value: 'Wohnen',
            label: 'Wohnbebauung',
            symbol: { kind: 'fill', fillColor: '#0072b2', fillOpacity: 0.5, outlineColor: '#004c77', outlineWidth: 1 },
          },
          {
            value: 'Gewerbe',
            label: 'Gewerbe',
            symbol: { kind: 'fill', fillColor: '#d55e00', fillOpacity: 0.5, outlineColor: '#8e3f00', outlineWidth: 1 },
          },
        ],
        fallbackSymbol: {
          kind: 'fill',
          fillColor: '#a3a3a3',
          fillOpacity: 0.5,
          outlineColor: '#737373',
          outlineWidth: 1,
        },
      },
      opacity: 1,
    }

    expect(styleToMapLibre(style, makeLayer(), SOURCE_ID)).toMatchSnapshot()
  })

  it('graduated', () => {
    const style: LayerStyle = {
      version: 1,
      renderer: {
        type: 'graduated',
        field: 'einwohner',
        classes: [
          { min: 0, max: 120, label: '0 – 120', symbol: { kind: 'line', color: '#eff5fb', width: 1 } },
          { min: 120, max: 340, label: '120 – 340', symbol: { kind: 'line', color: '#6baed6', width: 2 } },
          { min: 340, max: 780, label: '340 – 780', symbol: { kind: 'line', color: '#08306b', width: 3 } },
        ],
        fallbackSymbol: { kind: 'line', color: '#d4d4d4', width: 1 },
      },
      opacity: 1,
    }

    expect(styleToMapLibre(style, makeLayer({ geometryType: 'MULTILINESTRING' }), SOURCE_ID)).toMatchSnapshot()
  })

  it('mit Beschriftung', () => {
    const style: LayerStyle = {
      version: 1,
      renderer: {
        type: 'single',
        symbol: { kind: 'marker', shape: 'circle', size: 5, fillColor: '#e74c3c', strokeColor: '#ffffff', strokeWidth: 1 },
      },
      labels: {
        enabled: true,
        field: 'name',
        size: 12,
        color: '#333333',
        haloColor: '#ffffff',
        haloWidth: 1.5,
        minZoom: 14,
        allowOverlap: false,
      },
      opacity: 1,
    }

    expect(styleToMapLibre(style, makeLayer({ geometryType: 'MULTIPOINT' }), SOURCE_ID)).toMatchSnapshot()
  })

  it('GEOMETRY-Layer, kategorisiert', () => {
    const style: LayerStyle = {
      version: 1,
      renderer: {
        type: 'categorized',
        field: 'art',
        categories: [
          {
            value: 'A',
            label: 'A',
            symbol: { kind: 'fill', fillColor: '#0072b2', fillOpacity: 0.4, outlineColor: '#004c77', outlineWidth: 1 },
          },
        ],
        fallbackSymbol: { kind: 'fill', fillColor: '#a3a3a3', fillOpacity: 0.4, outlineColor: '#737373', outlineWidth: 1 },
      },
      labels: {
        enabled: true,
        field: 'name',
        size: 11,
        color: '#262626',
        haloColor: '#ffffff',
        haloWidth: 1.5,
        minZoom: 12,
        allowOverlap: false,
      },
      opacity: 0.9,
    }

    expect(styleToMapLibre(style, makeLayer({ geometryType: 'GEOMETRY' }), SOURCE_ID)).toMatchSnapshot()
  })
})

describe('styleToMapLibre Reihenfolge und Ids', () => {
  it('hängt den Label-Layer hinten an, damit die Reihenfolge bottom-to-top bleibt', () => {
    const style: LayerStyle = {
      ...defaultStyleFor('GEOMETRY'),
      labels: {
        enabled: true,
        field: 'name',
        size: 12,
        color: '#262626',
        haloColor: '#ffffff',
        haloWidth: 1.5,
        minZoom: 12,
        allowOverlap: false,
      },
    }

    expect(styleToMapLibre(style, makeLayer({ geometryType: 'GEOMETRY' }), SOURCE_ID).map((spec) => spec.id)).toEqual([
      'hgis-layer-layer-1-polygon',
      'hgis-layer-layer-1-line',
      'hgis-layer-layer-1-point',
      'hgis-layer-layer-1-label',
    ])
  })

  it('lässt die Beschriftung weg, wenn sie aus ist oder kein Feld hat', () => {
    const base = defaultStyleFor('MULTIPOLYGON')
    const labels = {
      enabled: false,
      field: 'name',
      size: 12,
      color: '#262626',
      haloColor: '#ffffff',
      haloWidth: 1.5,
      minZoom: 12,
      allowOverlap: false,
    }

    expect(styleToMapLibre({ ...base, labels }, makeLayer(), SOURCE_ID)).toHaveLength(1)
    expect(
      styleToMapLibre({ ...base, labels: { ...labels, enabled: true, field: '' } }, makeLayer(), SOURCE_ID),
    ).toHaveLength(1)
  })

  it('verengt den Zoombereich des Layers, weitet ihn aber nie', () => {
    const style: LayerStyle = { ...defaultStyleFor('MULTIPOLYGON'), minZoom: 8, maxZoom: 30 }
    const [spec] = styleToMapLibre(style, makeLayer({ minZoom: 2, maxZoom: 18 }), SOURCE_ID)

    expect(spec.minzoom).toBe(8)
    expect(spec.maxzoom).toBe(18)
  })
})

describe('styleToMapLibre Strichart', () => {
  // Cast on purpose: the whole point is a payload the type says cannot occur -- a
  // symbol whose optional member the server left out of the JSON entirely.
  function lineStyle(symbol: Record<string, unknown>): LayerStyle {
    return { version: 1, renderer: { type: 'single', symbol }, opacity: 1 } as unknown as LayerStyle
  }

  function dashOf(style: LayerStyle): unknown {
    const [spec] = styleToMapLibre(style, makeLayer({ geometryType: 'MULTILINESTRING' }), SOURCE_ID)
    return (spec as LineLayerSpecification).paint?.['line-dasharray']
  }

  /**
   * The server omits every null member, so a solid line comes back with no `dashArray`
   * key at all rather than with `null`. Both have to mean the same thing here.
   */
  it('zeichnet durchgezogen, ob dashArray fehlt, null oder leer ist', () => {
    const solid = { kind: 'line', color: '#404040', width: 1.25 }

    expect(dashOf(lineStyle(solid))).toBeUndefined()
    expect(dashOf(lineStyle({ ...solid, dashArray: null }))).toBeUndefined()
    expect(dashOf(lineStyle({ ...solid, dashArray: [] }))).toBeUndefined()
  })

  it('übernimmt ein Strichmuster unverändert', () => {
    expect(dashOf(lineStyle({ kind: 'line', color: '#404040', width: 1.25, dashArray: [3, 2] }))).toEqual([3, 2])
  })
})

describe('styleToMapLibre Ausdrücke', () => {
  function fillColorOf(style: LayerStyle): unknown {
    const [spec] = styleToMapLibre(style, makeLayer(), SOURCE_ID)
    return (spec as FillLayerSpecification).paint?.['fill-color']
  }

  function categorized(values: (string | number | null)[], colors: string[]): LayerStyle {
    return {
      version: 1,
      renderer: {
        type: 'categorized',
        field: 'art',
        categories: values.map((value, index) => ({
          value,
          label: String(value),
          symbol: {
            kind: 'fill',
            fillColor: colors[index],
            fillOpacity: 0.5,
            outlineColor: '#000000',
            outlineWidth: 1,
          },
        })),
        fallbackSymbol: { kind: 'fill', fillColor: '#a3a3a3', fillOpacity: 0.5, outlineColor: '#000000', outlineWidth: 1 },
      },
      opacity: 1,
    }
  }

  it('lässt Kategorien ohne Wert aus -- MapLibre trifft null nie und nimmt den Fallback', () => {
    expect(fillColorOf(categorized([null, 'B'], ['#111111', '#222222']))).toEqual([
      'match',
      ['get', 'art'],
      'B',
      '#222222',
      '#a3a3a3',
    ])
  })

  it('behält bei doppelten Werten die erste Kategorie -- doppelte Labels sind ein Parse-Fehler', () => {
    expect(fillColorOf(categorized(['A', 'A'], ['#111111', '#222222']))).toEqual([
      'match',
      ['get', 'art'],
      'A',
      '#111111',
      '#a3a3a3',
    ])
  })

  it('fällt auf eine Konstante zurück, wenn alle Zweige dieselbe Farbe hätten', () => {
    expect(fillColorOf(categorized(['A', 'B'], ['#a3a3a3', '#a3a3a3']))).toBe('#a3a3a3')
  })

  function graduated(mins: number[], colors: string[]): LayerStyle {
    return {
      version: 1,
      renderer: {
        type: 'graduated',
        field: 'einwohner',
        classes: mins.map((min, index) => ({
          min,
          max: mins[index + 1] ?? min,
          label: String(min),
          symbol: {
            kind: 'fill',
            fillColor: colors[index],
            fillOpacity: 0.5,
            outlineColor: '#000000',
            outlineWidth: 1,
          },
        })),
        fallbackSymbol: { kind: 'fill', fillColor: '#d4d4d4', fillOpacity: 0.5, outlineColor: '#000000', outlineWidth: 1 },
      },
      opacity: 1,
    }
  }

  it('sichert step gegen fehlende Werte ab, damit sie im Fallback landen', () => {
    expect(fillColorOf(graduated([0, 100], ['#111111', '#222222']))).toEqual([
      'case',
      ['has', 'einwohner'],
      ['step', ['get', 'einwohner'], '#111111', 100, '#222222'],
      '#d4d4d4',
    ])
  })

  it('verwirft Klassengrenzen, die nicht streng steigen -- Quantile wiederholen sich', () => {
    expect(fillColorOf(graduated([0, 0, 100], ['#111111', '#222222', '#333333']))).toEqual([
      'case',
      ['has', 'einwohner'],
      ['step', ['get', 'einwohner'], '#111111', 100, '#333333'],
      '#d4d4d4',
    ])
  })

  it('trennt konstante von klassifizierten Eigenschaften: gleiche Breite bleibt eine Zahl', () => {
    const style = graduated([0, 100], ['#111111', '#222222'])
    const [spec] = styleToMapLibre(style, makeLayer(), SOURCE_ID)

    expect((spec as FillLayerSpecification).paint?.['fill-opacity']).toBe(0.5)
    expect((spec as FillLayerSpecification).paint?.['fill-color']).toBeInstanceOf(Array)
  })
})

describe('styleToMapLibre heatmap', () => {
  function heatmapStyle(
    overrides: Partial<Extract<LayerStyle['renderer'], { type: 'heatmap' }>> = {},
  ): LayerStyle {
    return {
      version: 1,
      renderer: { type: 'heatmap', field: null, radius: 30, intensity: 1, ramp: 'blues', ...overrides },
      opacity: 1,
    }
  }

  function heatmapPaintOf(
    style: LayerStyle,
    layer: VectorLayerSummary = makeLayer(),
    fieldRange?: FieldRangeState,
  ): NonNullable<HeatmapLayerSpecification['paint']> {
    const [spec] = styleToMapLibre(style, layer, SOURCE_ID, fieldRange)
    return (spec as HeatmapLayerSpecification).paint ?? {}
  }

  it('zählt ohne Feld jedes Objekt gleich -- konstantes Gewicht 1', () => {
    expect(heatmapPaintOf(heatmapStyle())['heatmap-weight']).toBe(1)
  })

  it('normiert das Gewicht auf 0..1, wenn ein Feld und dessen Spanne vorliegen', () => {
    const paint = heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }), makeLayer(), { min: 0, max: 70 })

    expect(paint['heatmap-weight']).toEqual([
      'case',
      ['!', ['has', 'laut_wert']],
      0,
      ['interpolate', ['linear'], ['to-number', ['get', 'laut_wert'], 0], 0, 0, 70, 1],
    ])
  })

  it('fällt auf ein konstantes Gewicht zurück, solange die Spanne des Feldes noch nicht geladen ist', () => {
    // Kein fieldRange übergeben -- der Zustand, bevor MapLayerSync die Antwort von
    // heatmapFieldRangeQuery hat.
    expect(heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }))['heatmap-weight']).toBe(1)
  })

  it('fällt auf ein konstantes Gewicht zurück, wenn jedes Objekt denselben Wert trägt', () => {
    const paint = heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }), makeLayer(), { min: 5, max: 5 })

    expect(paint['heatmap-weight']).toBe(1)
  })

  /**
   * Team-Review nach package 2: eine Expression, die richtig aussieht, ist nicht
   * dasselbe wie eine, die richtig rechnet. Ausgewertet mit MapLibres eigenem
   * Interpreter (`@maplibre/maplibre-gl-style-spec`'s `createExpression`), nicht nur an
   * der erzeugten Struktur geprüft -- genau das hätte den ursprünglichen Fehler
   * gefunden: `to-number`s zweites Argument (der Fallback) wird nur für einen Wert
   * erreicht, der nicht in eine Zahl umgewandelt werden kann, z. B. eine Zeichenkette --
   * für ein fehlendes Feld liefert `['get', field]` `null`, und MapLibres `Coercion`
   * bricht die `'number'`-Umwandlung dafür sofort mit `0` ab, *bevor* der Fallback
   * überhaupt betrachtet wird. Ohne einen expliziten `!has`-Zweig davor landete dieses
   * `0` im `interpolate` und wurde je nach Vorzeichen der Spanne zu 0, zur Mitte oder --
   * bei einer rein negativen Spanne -- zum Maximalgewicht.
   */
  function evaluateHeatmapWeight(expression: unknown, properties: Record<string, unknown>): number {
    const parsed = createExpression(expression, 'heatmap-weight')
    if (parsed.result !== 'success') {
      throw new Error(`Expression konnte nicht geparst werden: ${JSON.stringify(parsed.value)}`)
    }
    // `type` here is MapLibre's own geometry-kind discriminator, not GeoJSON's -- a
    // heatmap tile carries points, and no expression here reads the geometry anyway.
    return parsed.value.evaluate({ zoom: 10 }, { type: 'Point', properties }) as number
  }

  it('gewichtet ein Objekt ohne Feldwert immer mit 0 -- unabhängig vom Vorzeichen der Spanne', () => {
    const positiveRange = heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }), makeLayer(), { min: 10, max: 100 })
    const straddlingRange = heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }), makeLayer(), { min: -50, max: 70 })
    const negativeRange = heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }), makeLayer(), { min: -100, max: -10 })

    expect(evaluateHeatmapWeight(positiveRange['heatmap-weight'], {})).toBe(0)
    expect(evaluateHeatmapWeight(straddlingRange['heatmap-weight'], {})).toBe(0)
    // Der eigentliche Fund: bei rein negativer Spanne wurde ein Objekt ohne Wert vorher
    // zum hellsten Punkt der Karte (Gewicht 1) statt zum dunkelsten.
    expect(evaluateHeatmapWeight(negativeRange['heatmap-weight'], {})).toBe(0)
  })

  it('interpoliert einen vorhandenen Wert weiterhin korrekt zwischen den Rändern', () => {
    const paint = heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }), makeLayer(), { min: 0, max: 70 })
    const weight = paint['heatmap-weight']

    expect(evaluateHeatmapWeight(weight, { laut_wert: 0 })).toBe(0)
    expect(evaluateHeatmapWeight(weight, { laut_wert: 35 })).toBe(0.5)
    expect(evaluateHeatmapWeight(weight, { laut_wert: 70 })).toBe(1)
  })

  it('klammert Radius und Intensität an ihre Grenzen', () => {
    const paint = heatmapPaintOf(heatmapStyle({ radius: 500, intensity: 20 }))

    expect(paint['heatmap-radius']).toBe(100)
    expect(paint['heatmap-intensity']).toBe(5)
  })

  it('setzt heatmap-opacity nur, wenn sie von 1 abweicht', () => {
    const opaque = heatmapPaintOf({ ...heatmapStyle(), opacity: 1 })
    const halved = heatmapPaintOf({ ...heatmapStyle(), opacity: 0.5 })

    expect(opaque['heatmap-opacity']).toBeUndefined()
    expect(halved['heatmap-opacity']).toBe(0.5)
  })

  it('färbt über heatmap-density, nicht über das Feld -- transparenter Anfang, deckende Spitze', () => {
    const color = heatmapPaintOf(heatmapStyle({ ramp: 'blues' }))['heatmap-color'] as unknown[]

    expect(color[0]).toBe('interpolate')
    expect(color[2]).toEqual(['heatmap-density'])
    expect(color[3]).toBe(0)
    expect(color[4]).toMatch(/^rgba\(\d+, \d+, \d+, 0\)$/)
    expect(color[color.length - 2]).toBe(1)
  })

  /**
   * Team-Review nach package 2: "lädt noch" und "wird nie kommen" sahen vorher gleich
   * aus -- beide fielen auf ein konstantes Gewicht *und* die normale Rampe zurück, eine
   * Karte, die aussieht, als hätte die Normierung funktioniert, obwohl sie es nicht hat.
   * `'error'` bekommt deshalb eine eigene Farbe, das Gewicht bleibt bei beiden gleich.
   */
  it.each(['error', 'invalid'] as const)(
    'faerbt bei bestaetigt fehlender Spanne (%s) diagnostisch statt mit der gewaehlten Rampe',
    (state) => {
      const working = heatmapPaintOf(heatmapStyle({ field: 'laut_wert', ramp: 'greys' }), makeLayer(), { min: 0, max: 70 })
      const failed = heatmapPaintOf(heatmapStyle({ field: 'laut_wert', ramp: 'greys' }), makeLayer(), state)

      expect(failed['heatmap-color']).not.toEqual(working['heatmap-color'])
      // Grau ist ein gültiger Katalogeintrag (`greys`) -- die Diagnosefarbe darf ihm daher
      // nicht gleichen, sonst waere sie fuer genau die Person unsichtbar, die Grau mag.
      expect(JSON.stringify(failed['heatmap-color'])).not.toContain('#404040')
    },
  )

  it.each(['error', 'invalid'] as const)(
    'faellt bei bestaetigt fehlender Spanne (%s) trotzdem auf ein konstantes Gewicht zurueck, nicht auf 0',
    (state) => {
      // Ein unsichtbarer Layer waere kein Signal, nur ein weiteres Symptom -- die Karte
      // zeigt weiterhin ehrlich, wo die Objekte liegen.
      expect(heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }), makeLayer(), state)['heatmap-weight']).toBe(1)
    },
  )

  it('faerbt "error" und "invalid" auf der Karte gleich -- sie unterscheiden sich nur im Toast-Text', () => {
    const asError = heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }), makeLayer(), 'error')
    const asInvalid = heatmapPaintOf(heatmapStyle({ field: 'laut_wert' }), makeLayer(), 'invalid')

    expect(asError).toEqual(asInvalid)
  })

  it('rendert einen GEOMETRY-Layer als einen einzigen Punkt-Sublayer statt der Dreifach-Aufteilung', () => {
    const specs = styleToMapLibre(heatmapStyle(), makeLayer({ geometryType: 'GEOMETRY' }), SOURCE_ID)

    expect(specs.map((spec) => spec.id)).toEqual(['hgis-layer-layer-1-render'])
    expect(specs[0].type).toBe('heatmap')
  })

  it('erzwingt Punkt-Platzierung für Beschriftungen -- die Kachel liefert nur Punkte', () => {
    const style: LayerStyle = {
      ...heatmapStyle(),
      labels: {
        enabled: true,
        field: 'name',
        size: 12,
        color: '#262626',
        haloColor: '#ffffff',
        haloWidth: 1.5,
        minZoom: 10,
        allowOverlap: false,
      },
    }

    const specs = styleToMapLibre(style, makeLayer({ geometryType: 'MULTILINESTRING' }), SOURCE_ID)
    const label = specs[specs.length - 1] as SymbolLayerSpecification

    expect(specs.map((spec) => spec.id)).toEqual(['hgis-layer-layer-1-render', 'hgis-layer-layer-1-label'])
    expect(label.type).toBe('symbol')
    expect(label.layout?.['symbol-placement']).toBe('point')
  })
})
