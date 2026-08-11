import { describe, expect, it } from 'vitest'
import type { ClassifyMethod, LayerField } from '@/api/layers'
import { sourceNameOfField } from './classification'
import { COLOR_RAMPS } from './defaults'
import { DASH_LABELS, METHOD_LABELS, RENDERER_LABELS, dashKeyOf, labelOf, paletteLabel } from './labels'
import { DEFAULT_CATEGORY_PALETTE } from './palettes'
import type { RendererType } from './types'

/**
 * Base UI renders the raw `value` in a select trigger, not the text of the chosen item.
 * Every one of these values is technical -- a renderer name, a method name, a field's
 * uuid -- so without a translation the panel showed `categorized` and a bare uuid to the
 * user. What is checked here is that a translation exists for every value the menus can
 * produce; that each `<SelectValue>` actually calls one is the part only the browser
 * shows, and the reason this slipped through in the first place.
 */

const FIELDS: LayerField[] = [
  { id: '019fecb8-6f22-737c-bbd2-2c9295bd8731', sourceName: 'Baujahr', columnName: 'baujahr', dataType: 'bigint' },
]

describe('Beschriftungen der Auswahlfelder', () => {
  it('übersetzt jeden Renderer-Typ, keiner bleibt technisch', () => {
    const types: RendererType[] = ['single', 'categorized', 'graduated']

    for (const type of types) {
      expect(RENDERER_LABELS.some(([value]) => value === type), type).toBe(true)
      expect(labelOf(RENDERER_LABELS, type)).not.toBe(type)
    }
  })

  it('übersetzt jede Klassifizierungsmethode', () => {
    const methods: ClassifyMethod[] = ['quantile', 'equalInterval', 'naturalBreaks']

    for (const method of methods) {
      expect(METHOD_LABELS.some(([value]) => value === method), method).toBe(true)
      expect(labelOf(METHOD_LABELS, method)).not.toBe(method)
    }
  })

  it('übersetzt jede Palette, die das Auswahlfeld anbietet', () => {
    expect(paletteLabel(DEFAULT_CATEGORY_PALETTE)).toBe('Kategorien')
    for (const ramp of COLOR_RAMPS) {
      expect(paletteLabel(ramp.id)).toBe(ramp.label)
      expect(paletteLabel(ramp.id)).not.toBe(ramp.id)
    }
  })

  it('übersetzt jede Strichart', () => {
    for (const [key] of DASH_LABELS) {
      expect(labelOf(DASH_LABELS, key)).not.toBe(key)
    }
  })

  /** The one the lead saw: `019fecb8-…` standing where `Baujahr` belongs. */
  it('zeigt für ein Feld den Quellnamen, nicht die Id', () => {
    expect(sourceNameOfField(FIELDS, FIELDS[0].id)).toBe('Baujahr')
    expect(sourceNameOfField(FIELDS, FIELDS[0].id)).not.toContain('-')
  })

  it('zeigt nichts statt einer Id, wenn das Feld den Layer nicht mehr gibt', () => {
    expect(sourceNameOfField(FIELDS, 'weg')).toBe('')
  })

  it('gibt einen unbekannten Wert unverändert zurück, statt gar nichts anzuzeigen', () => {
    expect(labelOf(RENDERER_LABELS, 'unbekannt')).toBe('unbekannt')
  })
})

describe('dashKeyOf', () => {
  it('erkennt die vordefinierten Muster zurück', () => {
    expect(dashKeyOf(null)).toBe('solid')
    expect(dashKeyOf(undefined)).toBe('solid')
    expect(dashKeyOf([])).toBe('solid')
    expect(dashKeyOf([3, 2])).toBe('dashed')
    expect(dashKeyOf([1, 2])).toBe('dotted')
  })

  it('nennt ein fremdes Muster gestrichelt, statt die Auswahl leer zu lassen', () => {
    expect(dashKeyOf([5, 5, 1])).toBe('dashed')
  })
})
