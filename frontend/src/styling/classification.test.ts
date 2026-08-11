import { describe, expect, it } from 'vitest'
import type { FillLayerSpecification, SymbolLayerSpecification } from 'maplibre-gl'
import type { LayerField, LayerSummary } from '@/api/layers'
import {
  buildCategories,
  buildClasses,
  columnNameOfField,
  fieldIdOfColumn,
} from './classification'
import { defaultLabels, defaultSymbolFor, primaryColorOf } from './defaults'
import { DEFAULT_CATEGORY_PALETTE, DEFAULT_RAMP } from './palettes'
import { styleToMapLibre } from './styleToMapLibre'
import type { LayerStyle } from './types'

/**
 * The source names carry umlauts, the column names are normalised -- exactly the shape
 * of this project's own test data, and the reason a style may never hold a source name.
 */
const FIELDS: LayerField[] = [
  { id: 'f1', sourceName: 'Straße', columnName: 'strasse', dataType: 'text' },
  { id: 'f2', sourceName: 'Höhe', columnName: 'hoehe', dataType: 'double precision' },
]

function makeLayer(overrides: Partial<LayerSummary> = {}): LayerSummary {
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
