import { describe, expect, it } from 'vitest'
import type { CircleLayerSpecification, FillLayerSpecification, SymbolLayerSpecification } from 'maplibre-gl'
import type { LayerField, VectorLayerSummary } from '@/api/layers'
import {
  buildCategories,
  buildClasses,
  columnNameOfField,
  fieldIdOfColumn,
  sharedSymbolOf,
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
