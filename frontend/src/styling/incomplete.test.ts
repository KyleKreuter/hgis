import { describe, expect, it } from 'vitest'
import type { LayerSpecification } from 'maplibre-gl'
import type { GeometryType, LayerSummary } from '@/api/layers'
import { styleToMapLibre } from './styleToMapLibre'
import type { LayerStyle } from './types'

/**
 * The server omits every null member, so a style that validates server-side can still
 * arrive here missing almost everything -- a fill symbol with `kind` and a colour and no
 * `outlineColor` is a legal payload.
 *
 * MapLibre answers one `undefined` paint value by discarding the **whole layer**:
 *
 *     layers.hgis-layer-x-render.paint.fill-outline-color: color expected, undefined found
 *
 * The objects are then gone from the map and from `map.getStyle().layers`, with that one
 * console line as the only sign. That is why this is checked over every geometry type
 * and every renderer rather than at the one spot where it was found.
 */

const GEOMETRY_TYPES: GeometryType[] = ['MULTIPOINT', 'MULTILINESTRING', 'MULTIPOLYGON', 'GEOMETRY']

function makeLayer(geometryType: GeometryType): LayerSummary {
  return {
    id: 'layer-1',
    name: 'Gebäude',
    geometryType,
    srid: 25832,
    featureCount: 100,
    visible: true,
    zIndex: 0,
    minZoom: 0,
    maxZoom: 22,
    dataVersion: 1,
    styleVersion: 1,
    extent: null,
  }
}

/** Casts on purpose: these are payloads the TypeScript types say cannot exist. */
function asStyle(style: unknown): LayerStyle {
  return style as LayerStyle
}

/** The paint union has no common index signature; the test only ever reads by name. */
function paintOf(spec: LayerSpecification): Record<string, unknown> {
  return (spec.paint ?? {}) as Record<string, unknown>
}

function undefinedProperties(specs: LayerSpecification[]): string[] {
  return specs.flatMap((spec) => {
    const groups: [string, Record<string, unknown> | undefined][] = [
      ['paint', paintOf(spec)],
      ['layout', spec.layout as Record<string, unknown> | undefined],
    ]
    return groups.flatMap(([group, properties]) =>
      Object.entries(properties ?? {})
        .filter(([, value]) => value === undefined || (typeof value === 'number' && !Number.isFinite(value)))
        .map(([name]) => `${spec.id}.${group}.${name}`),
    )
  })
}

describe('unvollständige Symbole', () => {
  it('erzeugt aus einem Symbol mit nur kind einen vollständigen Paint', () => {
    for (const kind of ['fill', 'line', 'marker']) {
      for (const geometryType of GEOMETRY_TYPES) {
        const specs = styleToMapLibre(
          asStyle({ version: 1, renderer: { type: 'single', symbol: { kind } }, opacity: 1 }),
          makeLayer(geometryType),
          'hgis-layer-layer-1',
        )

        expect(specs.length).toBeGreaterThan(0)
        expect(undefinedProperties(specs), `${kind} / ${geometryType}`).toEqual([])
      }
    }
  })

  /** The case found on the running system: a fill symbol without an outline colour. */
  it('füllt eine fehlende Umrissfarbe auf, statt den Layer zu verlieren', () => {
    const specs = styleToMapLibre(
      asStyle({
        version: 1,
        renderer: { type: 'single', symbol: { kind: 'fill', fillColor: '#27ae60', fillOpacity: 0.5 } },
        opacity: 1,
      }),
      makeLayer('MULTIPOLYGON'),
      'hgis-layer-layer-1',
    )

    expect(paintOf(specs[0])['fill-outline-color']).toBe('#262626')
    expect(paintOf(specs[0])['fill-color']).toBe('#27ae60')
  })

  it('übersteht Kategorien und Klassen, deren Symbole nur kind tragen', () => {
    const categorized = asStyle({
      version: 1,
      renderer: {
        type: 'categorized',
        field: 'art',
        categories: [{ value: 'A', label: 'A', symbol: { kind: 'fill' } }],
        fallbackSymbol: { kind: 'fill' },
      },
      opacity: 1,
    })
    const graduated = asStyle({
      version: 1,
      renderer: {
        type: 'graduated',
        field: 'hoehe',
        classes: [
          { min: 0, max: 10, label: '0 – 10', symbol: { kind: 'fill' } },
          { min: 10, max: 20, label: '10 – 20', symbol: { kind: 'line' } },
        ],
        fallbackSymbol: { kind: 'marker' },
      },
      opacity: 1,
    })

    for (const geometryType of GEOMETRY_TYPES) {
      expect(undefinedProperties(styleToMapLibre(categorized, makeLayer(geometryType), 'src'))).toEqual([])
      expect(undefinedProperties(styleToMapLibre(graduated, makeLayer(geometryType), 'src'))).toEqual([])
    }
  })

  /**
   * `opacity` multiplies into the paint values, so a missing one produces NaN rather
   * than a wrong colour -- and MapLibre rejects that exactly as harshly.
   */
  it('überlebt einen Style ohne Deckkraft, ohne NaN zu erzeugen', () => {
    const specs = styleToMapLibre(
      asStyle({ version: 1, renderer: { type: 'single', symbol: { kind: 'fill' } } }),
      makeLayer('MULTIPOLYGON'),
      'src',
    )

    expect(undefinedProperties(specs)).toEqual([])
    expect(paintOf(specs[0])['fill-opacity']).toBe(0.25)
  })

  it('beschriftet mit vollständigem Paint, auch wenn nur Feld und Schalter gesetzt sind', () => {
    const specs = styleToMapLibre(
      asStyle({
        version: 1,
        renderer: { type: 'single', symbol: { kind: 'fill' } },
        labels: { enabled: true, field: 'name' },
        opacity: 1,
      }),
      makeLayer('MULTIPOLYGON'),
      'src',
    )

    const label = specs[specs.length - 1]
    expect(label.type).toBe('symbol')
    expect(undefinedProperties(specs)).toEqual([])
    expect(Number.isFinite(label.minzoom)).toBe(true)
  })
})
