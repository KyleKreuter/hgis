import { describe, expect, it } from 'vitest'
import type { LayerField } from '@/api/layers'
import { defaultStyleFor, defaultSymbolFor, primaryColorOf } from './defaults'
import { convertRenderer } from './renderer'
import type { LayerStyle } from './types'

const FIELDS: LayerField[] = [
  { id: '1', sourceName: 'Gebäudehöhe', columnName: 'gebaeudehoehe', dataType: 'double precision' },
  { id: '2', sourceName: 'Nutzungsart', columnName: 'nutzungsart', dataType: 'text' },
]

const BASE = defaultStyleFor('MULTIPOLYGON')

describe('convertRenderer', () => {
  it('nimmt die Farbe des bisherigen Symbols mit', () => {
    const categorized = convertRenderer(BASE, 'categorized', 'MULTIPOLYGON', FIELDS)
    const back = convertRenderer({ ...BASE, renderer: categorized }, 'single', 'MULTIPOLYGON', FIELDS)

    expect(back.type).toBe('single')
    expect(back.type === 'single' && primaryColorOf(back.symbol)).toBe('#a3a3a3')
  })

  it('startet klassifizierte Renderer ohne Feld, solange keins gewählt ist', () => {
    const categorized = convertRenderer(BASE, 'categorized', 'MULTIPOLYGON', FIELDS)

    expect(categorized).toMatchObject({ type: 'categorized', field: '', categories: [] })
  })

  it('behält ein numerisches Feld beim Wechsel auf abgestuft', () => {
    const style: LayerStyle = {
      ...BASE,
      renderer: convertRenderer(BASE, 'categorized', 'MULTIPOLYGON', FIELDS),
    }
    const withField: LayerStyle = {
      ...style,
      renderer: { ...(style.renderer as Extract<typeof style.renderer, { type: 'categorized' }>), field: 'gebaeudehoehe' },
    }

    expect(convertRenderer(withField, 'graduated', 'MULTIPOLYGON', FIELDS)).toMatchObject({
      field: 'gebaeudehoehe',
    })
  })

  /**
   * The gap found on the running system: a stored categorized/graduated renderer can
   * be missing `fallbackSymbol` (an older row, or a client that bypassed validation,
   * from before the server started requiring it). `symbolOf` used to return it
   * unguarded, so switching such a renderer to "single" produced a document whose
   * `symbol` member was `undefined` -- the same crash `styleToMapLibre.ts`'s
   * `dataDriven` has for the identical gap, just reachable one step earlier, by
   * picking "Einzelsymbol" in the renderer-type dropdown.
   */
  it('wechselt einen Renderer ohne fallbackSymbol auf ein Symbol, statt undefined zu liefern', () => {
    const style: LayerStyle = {
      ...BASE,
      renderer: {
        type: 'categorized',
        field: 'nutzungsart',
        categories: [],
      } as unknown as Extract<LayerStyle['renderer'], { type: 'categorized' }>,
    }

    const single = convertRenderer(style, 'single', 'MULTIPOLYGON', FIELDS)

    expect(single.type).toBe('single')
    expect(single.type === 'single' && single.symbol).toBeDefined()
    expect(single.type === 'single' && primaryColorOf(single.symbol)).toBe('#404040')
  })

  it('verwirft ein Textfeld beim Wechsel auf abgestuft -- /classify würde 400 antworten', () => {
    const style: LayerStyle = {
      ...BASE,
      // BASE is always `single` (`defaultStyleFor`), so its symbol is a plain fallback here.
      renderer: { type: 'categorized', field: 'nutzungsart', categories: [], fallbackSymbol: defaultSymbolFor('MULTIPOLYGON') },
    }

    expect(convertRenderer(style, 'graduated', 'MULTIPOLYGON', FIELDS)).toMatchObject({ field: '' })
  })

  it('startet eine Heatmap ohne Feld (Dichte), solange keins gewählt ist', () => {
    const heatmap = convertRenderer(BASE, 'heatmap', 'MULTIPOLYGON', FIELDS)

    expect(heatmap).toMatchObject({ type: 'heatmap', field: null, radius: 30, intensity: 1 })
  })

  it('behält ein numerisches Feld beim Wechsel auf Heatmap', () => {
    const style: LayerStyle = {
      ...BASE,
      renderer: { type: 'graduated', field: 'gebaeudehoehe', classes: [], fallbackSymbol: defaultSymbolFor('MULTIPOLYGON') },
    }

    expect(convertRenderer(style, 'heatmap', 'MULTIPOLYGON', FIELDS)).toMatchObject({ field: 'gebaeudehoehe' })
  })

  it('verwirft ein Textfeld beim Wechsel auf Heatmap -- die Gewichtung braucht ein Zahlenfeld', () => {
    const style: LayerStyle = {
      ...BASE,
      renderer: { type: 'categorized', field: 'nutzungsart', categories: [], fallbackSymbol: defaultSymbolFor('MULTIPOLYGON') },
    }

    expect(convertRenderer(style, 'heatmap', 'MULTIPOLYGON', FIELDS)).toMatchObject({ field: null })
  })

  it('nimmt beim Verlassen der Heatmap das Standardsymbol mit -- eine Heatmap hat keins eigenes', () => {
    const style: LayerStyle = { ...BASE, renderer: { type: 'heatmap', field: null, radius: 30, intensity: 1, ramp: 'blues' } }
    const back = convertRenderer(style, 'single', 'MULTIPOLYGON', FIELDS)

    expect(back.type).toBe('single')
    expect(back.type === 'single' && primaryColorOf(back.symbol)).toBe(primaryColorOf(defaultSymbolFor('MULTIPOLYGON')))
  })

  it('behält das gewählte Feld beim Wechsel von Heatmap auf abgestuft', () => {
    const style: LayerStyle = {
      ...BASE,
      renderer: { type: 'heatmap', field: 'gebaeudehoehe', radius: 30, intensity: 1, ramp: 'blues' },
    }

    expect(convertRenderer(style, 'graduated', 'MULTIPOLYGON', FIELDS)).toMatchObject({ field: 'gebaeudehoehe' })
  })
})
