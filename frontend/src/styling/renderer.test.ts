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
