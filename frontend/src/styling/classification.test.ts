import { afterEach, describe, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import type { CircleLayerSpecification, FillLayerSpecification, SymbolLayerSpecification } from 'maplibre-gl'
import type { LayerField, VectorLayerSummary } from '@/api/layers'
import {
  buildCategories,
  buildClasses,
  columnNameOfField,
  fieldIdOfColumn,
  requestCategorizedCategories,
  requestGraduatedClasses,
  requestHeatmapWeightSuggestion,
  resolveRangeState,
  resolveWeightBounds,
  sharedSymbolOf,
  weightSuggestionFromBreaks,
  withSharedSymbol,
  withSharedSymbolShape,
} from './classification'
import { defaultLabels, defaultSymbolFor, primaryColorOf, withPrimaryColor } from './defaults'
import { DEFAULT_CATEGORY_PALETTE, DEFAULT_RAMP } from './palettes'
import { styleToMapLibre } from './styleToMapLibre'
import type { LayerStyle, MarkerSymbol } from './types'

/**
 * The source names carry umlauts, the column names are normalised -- exactly the shape
 * of this project's own test data, and the reason a style may never hold a source name.
 */
const FIELDS: LayerField[] = [
  { id: 'f1', sourceName: 'Straße', columnName: 'strasse', dataType: 'text' },
  { id: 'f2', sourceName: 'Höhe', columnName: 'hoehe', dataType: 'double precision' },
]

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

describe('Feldauflösung', () => {
  it('übersetzt die Feld-Id des Auswahlfeldes in den Spaltennamen', () => {
    expect(columnNameOfField(FIELDS, 'f1')).toBe('strasse')
    expect(columnNameOfField(FIELDS, 'f2')).toBe('hoehe')
  })

  it('findet die Feld-Id zu einem gespeicherten Style zurück, um das Feld vorzuwählen', () => {
    expect(fieldIdOfColumn(FIELDS, 'strasse')).toBe('f1')
  })

  /**
   * A field that the layer no longer has must not preselect a wrong one. Empty leaves
   * the picker on its placeholder, and `isPersistable` keeps that state off the wire.
   */
  it('wählt nichts vor, wenn die Spalte den Layer nicht mehr gibt', () => {
    expect(fieldIdOfColumn(FIELDS, 'geloescht')).toBe('')
    expect(columnNameOfField(FIELDS, 'gibt-es-nicht')).toBe('')
  })
})

describe('Spaltenname bis in den MapLibre-Ausdruck', () => {
  /**
   * The failure this pins down is silent: with the source name in the style,
   * `['get', 'Straße']` finds nothing in a tile that carries `strasse`, and every
   * object drops to the fallback symbol without a single error anywhere.
   */
  it('kategorisiert über den Spaltennamen, nie über den Quellnamen', () => {
    const style: LayerStyle = {
      version: 1,
      renderer: {
        type: 'categorized',
        field: columnNameOfField(FIELDS, 'f1'),
        categories: buildCategories(
          [
            { value: 'Hauptweg', count: 812 },
            { value: null, count: 5 },
          ],
          'MULTIPOLYGON',
          DEFAULT_CATEGORY_PALETTE,
        ),
        fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      },
      opacity: 1,
    }

    const [spec] = styleToMapLibre(style, makeLayer(), 'hgis-layer-layer-1')
    const expression = (spec as FillLayerSpecification).paint?.['fill-color'] as unknown[]

    expect(expression[0]).toBe('match')
    expect(expression[1]).toEqual(['get', 'strasse'])
    expect(JSON.stringify(expression)).not.toContain('Straße')
  })

  it('stuft über den Spaltennamen ab', () => {
    const style: LayerStyle = {
      version: 1,
      renderer: {
        type: 'graduated',
        field: columnNameOfField(FIELDS, 'f2'),
        classes: buildClasses([0, 10, 20], 'MULTIPOLYGON', DEFAULT_RAMP),
        fallbackSymbol: defaultSymbolFor('MULTIPOLYGON'),
      },
      opacity: 1,
    }

    const [spec] = styleToMapLibre(style, makeLayer(), 'hgis-layer-layer-1')
    const expression = (spec as FillLayerSpecification).paint?.['fill-color'] as unknown[]

    expect(expression[0]).toBe('case')
    expect(expression[1]).toEqual(['has', 'hoehe'])
    expect((expression[2] as unknown[])[1]).toEqual(['get', 'hoehe'])
  })

  it('beschriftet über den Spaltennamen', () => {
    const style: LayerStyle = {
      version: 1,
      renderer: { type: 'single', symbol: defaultSymbolFor('MULTIPOLYGON') },
      labels: defaultLabels(columnNameOfField(FIELDS, 'f1')),
      opacity: 1,
    }

    const specs = styleToMapLibre(style, makeLayer(), 'hgis-layer-layer-1')
    const label = specs[specs.length - 1] as SymbolLayerSpecification

    expect(label.layout?.['text-field']).toEqual(['get', 'strasse'])
  })
})

describe('buildCategories', () => {
  it('lässt Objekte ohne Wert aus -- sie gehören zum Ersatzsymbol', () => {
    const categories = buildCategories(
      [
        { value: 'A', count: 3 },
        { value: null, count: 7 },
        { value: 'B', count: 1 },
      ],
      'MULTIPOLYGON',
      DEFAULT_CATEGORY_PALETTE,
    )

    expect(categories.map((category) => category.value)).toEqual(['A', 'B'])
    expect(categories[0].label).toBe('A')
    expect(primaryColorOf(categories[0].symbol)).not.toBe(primaryColorOf(categories[1].symbol))
  })
})

describe('buildClasses', () => {
  it('erzeugt eine Klasse weniger, als breaks Werte hat', () => {
    const classes = buildClasses([0, 120, 340, 780], 'MULTIPOLYGON', DEFAULT_RAMP)

    expect(classes).toHaveLength(3)
    expect(classes[0]).toMatchObject({ min: 0, max: 120 })
    expect(classes[2]).toMatchObject({ min: 340, max: 780 })
  })

  /**
   * The server drops repeated bounds, so fewer breaks than requested classes come back.
   * Reading the count from the answer is what keeps that from producing an empty class.
   */
  it('folgt der Antwort, wenn weniger Grenzen kommen als Klassen angefordert wurden', () => {
    expect(buildClasses([0, 5], 'MULTIPOLYGON', DEFAULT_RAMP)).toHaveLength(1)
    expect(buildClasses([], 'MULTIPOLYGON', DEFAULT_RAMP)).toHaveLength(0)
  })

  it('beschriftet die Klassen deutsch mit Gedankenstrich', () => {
    expect(buildClasses([0, 1200], 'MULTIPOLYGON', DEFAULT_RAMP)[0].label).toBe('0 – 1200')
  })
})

describe('sharedSymbolOf', () => {
  it('liest die Grösse aus dem ersten Klassensymbol, da alle Klassen sie teilen', () => {
    const classes = buildClasses([0, 10, 20], 'MULTIPOINT', DEFAULT_RAMP)
    const template: MarkerSymbol = { ...(classes[0].symbol as MarkerSymbol), size: 12 }
    const resized = withSharedSymbol(classes, template)

    expect(sharedSymbolOf(resized, defaultSymbolFor('MULTIPOINT'))).toMatchObject({ size: 12 })
  })

  /**
   * The case right after a field change: `selectField` empties the class list but
   * leaves `fallbackSymbol` untouched, so this is what keeps a size the user picked
   * earlier from being lost -- see the "übersteht einen Feldwechsel" test below for the
   * effect this has once the rebuild runs.
   */
  it('fällt auf das Fallback-Symbol zurück, wenn noch keine Klasse existiert', () => {
    const fallbackSymbol: MarkerSymbol = { ...(defaultSymbolFor('MULTIPOINT') as MarkerSymbol), size: 15 }
    expect(sharedSymbolOf([], fallbackSymbol)).toEqual(fallbackSymbol)
  })
})

describe('withSharedSymbolShape', () => {
  it('übernimmt die Form von template, behält aber die Farbe von symbol', () => {
    const symbol: MarkerSymbol = { ...(defaultSymbolFor('MULTIPOINT') as MarkerSymbol), fillColor: '#111111' }
    const template: MarkerSymbol = { ...(defaultSymbolFor('MULTIPOINT') as MarkerSymbol), size: 18, fillColor: '#eeeeee' }

    expect(withSharedSymbolShape(symbol, template)).toEqual({ ...template, fillColor: '#111111' })
  })
})

describe('withSharedSymbol', () => {
  it('übernimmt Grösse/Breite in alle Einträge, ohne deren Farbe zu verändern', () => {
    const classes = buildClasses([0, 10, 20, 30], 'MULTIPOINT', DEFAULT_RAMP)
    const colorsBefore = classes.map((styleClass) => primaryColorOf(styleClass.symbol))
    const template: MarkerSymbol = { ...(classes[0].symbol as MarkerSymbol), size: 20 }

    const resized = withSharedSymbol(classes, template)

    expect(resized.map((styleClass) => (styleClass.symbol as MarkerSymbol).size)).toEqual([20, 20, 20])
    expect(resized.map((styleClass) => primaryColorOf(styleClass.symbol))).toEqual(colorsBefore)
  })

  /**
   * What `GraduatedEditor`'s and `CategorizedEditor`'s rebuild effects rely on:
   * `buildClasses`/`buildCategories` reset every symbol to the layer's default shape,
   * so a size the user picked earlier has to be reapplied afterwards or it would
   * silently jump back to the default the moment the class count, method or ramp
   * changes.
   */
  it('überlebt den Neuaufbau der Klassen, der sonst auf das Standardsymbol zurückfiele', () => {
    const before = buildClasses([0, 10, 20], 'MULTIPOINT', DEFAULT_RAMP)
    const template: MarkerSymbol = { ...(before[0].symbol as MarkerSymbol), size: 15 }
    const resized = withSharedSymbol(before, template)

    const rebuilt = buildClasses([0, 5, 15, 25], 'MULTIPOINT', DEFAULT_RAMP)
    const carriedOver = withSharedSymbol(rebuilt, sharedSymbolOf(resized, defaultSymbolFor('MULTIPOINT')))

    expect(carriedOver).toHaveLength(3)
    expect(carriedOver.every((styleClass) => (styleClass.symbol as MarkerSymbol).size === 15)).toBe(true)
  })

  /**
   * A field change empties the class list (`selectField` sets `classes: []`) before the
   * rebuild effect runs, so the only place a previously-set size can still come from is
   * `fallbackSymbol` -- which is exactly why `setSharedSymbol` keeps it in step with the
   * classes (see `withSharedSymbolShape`). Without that, `sharedSymbolOf` would fall
   * back to the layer's plain default and the size would reset on every field change.
   */
  it('übersteht einen Feldwechsel, weil die Grösse dann aus dem Fallback-Symbol kommt', () => {
    const fallbackSymbol: MarkerSymbol = { ...(defaultSymbolFor('MULTIPOINT') as MarkerSymbol), size: 15 }

    const rebuiltAfterFieldChange = buildClasses([0, 10, 20], 'MULTIPOINT', DEFAULT_RAMP)
    const carriedOver = withSharedSymbol(rebuiltAfterFieldChange, sharedSymbolOf([], fallbackSymbol))

    expect(carriedOver.every((styleClass) => (styleClass.symbol as MarkerSymbol).size === 15)).toBe(true)
  })

  /** The case before any class exists yet -- nothing to apply the symbol to. */
  it('lässt eine leere Liste unverändert', () => {
    expect(withSharedSymbol([], defaultSymbolFor('MULTIPOINT'))).toEqual([])
  })
})

describe('gemeinsame Grösse bis in den MapLibre-Ausdruck', () => {
  /**
   * What `setSharedSymbol` does in both editors: apply one symbol's size to every class
   * and to the fallback, keeping each one's own colour. `circlePaint` collapses
   * `circle-radius` to a plain number whenever every branch -- fallback included --
   * would produce the same value (`dataDriven` in `styleToMapLibre.ts`); that collapse
   * is what proves the fallback actually carries the same size as the classes here. If
   * `setSharedSymbol` only touched the classes, as it did before, the fallback would
   * still hold the layer's default size and this would stay a `case`/`step` expression
   * with the *old* size baked into its last branch instead.
   */
  it('lässt circle-radius zu einer Konstante zusammenfallen, wenn Klassen und Fallback dieselbe Grösse tragen', () => {
    const template: MarkerSymbol = { ...(defaultSymbolFor('MULTIPOINT') as MarkerSymbol), size: 15 }
    const classes = withSharedSymbol(buildClasses([0, 10, 20], 'MULTIPOINT', DEFAULT_RAMP), template)
    const fallbackSymbol = withSharedSymbolShape(
      withPrimaryColor(defaultSymbolFor('MULTIPOINT'), '#a3a3a3'),
      template,
    )

    const style: LayerStyle = {
      version: 1,
      renderer: { type: 'graduated', field: 'hoehe', classes, fallbackSymbol },
      opacity: 1,
    }

    const [spec] = styleToMapLibre(style, makeLayer({ geometryType: 'MULTIPOINT' }), 'hgis-layer-layer-1')
    expect((spec as CircleLayerSpecification).paint?.['circle-radius']).toBe(15)
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
   * nicht-endlichen oder null Grenzen statt mit einem HTTP-Fehler. `range.max > range.min`
   * allein läse `null > null` (zu `0 > 0` gezwungen) bzw. `NaN > NaN` als `false` und
   * würde das lautlos in denselben Topf wie "lädt noch" werfen -- das ist genau der Fall,
   * den `styleToMapLibre`'s Diagnose-Verlauf braucht. `null` ist der tatsächliche
   * Wire-Wert von `/classify` bei einer leeren Spalte (`ClassifyResult.min`/`.max`,
   * `api/layers.ts`); `NaN`/`Infinity` sind hier nur die zusätzliche Absicherung.
   *
   * `'invalid'`, nicht `'error'`: eine erfolgreich beantwortete, aber unbrauchbare Anfrage
   * ist kein Transportproblem -- ein Neuladen der Seite ändert an genau diesem Ergebnis
   * nichts (`heatmapFieldRanges.ts`'s `rangeToastMessage` rät deshalb auch etwas anderes).
   */
  it('liest eine erfolgreich aufgelöste, aber nicht-endliche oder null Spanne als "invalid"', () => {
    expect(resolveRangeState({ isError: false, data: { min: NaN, max: NaN } })).toBe('invalid')
    expect(resolveRangeState({ isError: false, data: { min: 0, max: Infinity } })).toBe('invalid')
    expect(resolveRangeState({ isError: false, data: { min: null, max: null } })).toBe('invalid')
  })

  /**
   * Zweiter Fund der Teamrunde: `/classify` kann ein verdrehtes Paar praktisch nicht
   * liefern (`min`/`max` kommen aus derselben SQL-Aggregation über dieselbe Spalte, SQL
   * garantiert `min <= max`) -- aber ungeprüft wäre es dasselbe Bild über einen dritten
   * Weg: `heatmapWeight`s eigener Schutz (`max > min`) fiele still auf Gewicht 1 zurück,
   * während die Karte mit der gewählten Rampe statt der Diagnosefarbe zeichnet, weil
   * `resolveRangeState` das Paar für gültig hielt. Eine Zeile schließt das für immer.
   */
  it('liest ein verdrehtes Paar (min > max) als "invalid", obwohl der echte Endpunkt es nicht liefern kann', () => {
    expect(resolveRangeState({ isError: false, data: { min: 100, max: 10 } })).toBe('invalid')
  })
})

describe('weightSuggestionFromBreaks', () => {
  /** Zwölf Klassen, wie `WEIGHT_SUGGESTION_CLASSES` sie anfragt: 13 Bruchpunkte, wenn
   *  keiner der Werte doppelt vorkommt. */
  const TWELVE_CLASS_BREAKS = [0, 8, 16, 25, 33, 41, 50, 58, 66, 75, 83, 91, 100]

  it('nimmt den zweiten und den vorletzten Bruchpunkt -- nah an den Rändern, aber nicht die Ränder selbst', () => {
    expect(weightSuggestionFromBreaks(TWELVE_CLASS_BREAKS)).toEqual({ min: 8, max: 91 })
  })

  it('liefert nichts bei drei oder weniger Bruchpunkten -- kein echter innerer Quantilwert vorhanden', () => {
    expect(weightSuggestionFromBreaks([])).toBeUndefined()
    expect(weightSuggestionFromBreaks([10])).toBeUndefined()
    expect(weightSuggestionFromBreaks([10, 20])).toBeUndefined()
    expect(weightSuggestionFromBreaks([10, 20, 30])).toBeUndefined()
  })

  /**
   * Der Grenzfall, der die Vier-Bruchpunkte-Regel begründet: bei genau zwei
   * Bruchpunkten (Feld mit sehr wenigen unterschiedlichen Werten, nach dem
   * `strictlyAscending`-Dedup des Servers) ist Index 1 bereits das Maximum und Index
   * `length - 2` bereits das Minimum -- ohne die Untergrenze würde der "untere"
   * Vorschlag zum Maximum und umgekehrt, exakt vertauscht statt bloß ungenau.
   */
  it('vertauscht die Enden nicht bei zu wenigen Bruchpunkten', () => {
    expect(weightSuggestionFromBreaks([10, 20])).toBeUndefined()
  })

  it('liefert bei genau vier Bruchpunkten zwei unterschiedliche, aufsteigend geordnete innere Punkte', () => {
    expect(weightSuggestionFromBreaks([10, 20, 30, 40])).toEqual({ min: 20, max: 30 })
  })
})

describe('resolveWeightBounds', () => {
  /**
   * Spiegelt `LayerStyleService.requireWeightRange` (Backend, Paket 2): beides oder
   * keins, und `weightMax` muss echt größer sein -- Gleichstand zählt als Fehler, nicht
   * nur eine absteigende Spanne.
   */
  it('liefert das Paar, wenn beide gesetzt und aufsteigend sind', () => {
    expect(resolveWeightBounds(10, 20)).toEqual({ min: 10, max: 20 })
  })

  it('liefert nichts, wenn nur eine Seite gesetzt ist', () => {
    expect(resolveWeightBounds(10, undefined)).toBeUndefined()
    expect(resolveWeightBounds(undefined, 20)).toBeUndefined()
  })

  it('liefert nichts, wenn keine Seite gesetzt ist', () => {
    expect(resolveWeightBounds(undefined, undefined)).toBeUndefined()
  })

  it('liefert nichts bei Gleichstand -- der Server lehnt auch das ab, nicht nur eine absteigende Spanne', () => {
    expect(resolveWeightBounds(10, 10)).toBeUndefined()
  })

  it('liefert nichts bei absteigender Spanne', () => {
    expect(resolveWeightBounds(20, 10)).toBeUndefined()
  })

  it('behandelt 0 als echten Wert, nicht als "nicht gesetzt"', () => {
    expect(resolveWeightBounds(0, 10)).toEqual({ min: 0, max: 10 })
    expect(resolveWeightBounds(-10, 0)).toEqual({ min: -10, max: 0 })
  })
})

/**
 * Team review, package 2: `requestHeatmapWeightSuggestion`/`requestGraduatedClasses`/
 * `requestCategorizedCategories` all set `staleTime: 0` on their `fetchQuery` call, so a
 * user action -- pressing "Klassen neu berechnen", clicking the heatmap's suggestion
 * button -- always reaches the server, never a stale answer up to five minutes old from
 * `layerClassifyQuery`/`layerValuesQuery`'s own cache. Tested against the effect, not the
 * line itself: `staleTime: 0` reads as an easy "the query already has one" deletion during
 * cleanup, and nothing about the line's own shape would stop that -- only a test that
 * fails once the second call is served from the cache instead of asking again.
 */
describe('Frische bei Berechnungs-Aktionen (staleTime: 0)', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /** Answers every request with the same body, so two calls differ only in whether a
   *  second request was actually sent -- what each test here counts. */
  function stubGetJson(body: unknown) {
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  const CLASSIFY_BODY = { field: 'wert', method: 'quantile', breaks: [0, 1, 2, 3], min: 0, max: 3, nullCount: 0 }

  it('requestHeatmapWeightSuggestion fragt zweimal am Server nach, statt die zweite Antwort aus dem Zwischenspeicher zu bedienen', async () => {
    const fetchMock = stubGetJson(CLASSIFY_BODY)
    const queryClient = new QueryClient()

    await requestHeatmapWeightSuggestion(queryClient, 'layer-1', 'wert')
    await requestHeatmapWeightSuggestion(queryClient, 'layer-1', 'wert')

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('requestGraduatedClasses fragt zweimal am Server nach', async () => {
    const fetchMock = stubGetJson(CLASSIFY_BODY)
    const queryClient = new QueryClient()
    const symbol = defaultSymbolFor('MULTIPOLYGON')

    await requestGraduatedClasses(queryClient, 'layer-1', 'MULTIPOLYGON', 'wert', 'quantile', 3, 'blues', [], symbol)
    await requestGraduatedClasses(queryClient, 'layer-1', 'MULTIPOLYGON', 'wert', 'quantile', 3, 'blues', [], symbol)

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('requestCategorizedCategories fragt zweimal am Server nach', async () => {
    const fetchMock = stubGetJson({ field: 'wert', values: [{ value: 'a', count: 1 }], truncated: false })
    const queryClient = new QueryClient()
    const symbol = defaultSymbolFor('MULTIPOLYGON')

    await requestCategorizedCategories(queryClient, 'layer-1', 'MULTIPOLYGON', 'wert', 'blues', [], symbol)
    await requestCategorizedCategories(queryClient, 'layer-1', 'MULTIPOLYGON', 'wert', 'blues', [], symbol)

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
