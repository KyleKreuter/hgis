import { describe, expect, it } from 'vitest'
import type { LayerField } from '@/api/layers'
import { defaultStyleFor, primaryColorOf } from './defaults'
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
      renderer: { type: 'categorized', field: 'nutzungsart', categories: [], fallbackSymbol: BASE.renderer.type === 'single' ? BASE.renderer.symbol : BASE.renderer.fallbackSymbol },
    }

    expect(convertRenderer(style, 'graduated', 'MULTIPOLYGON', FIELDS)).toMatchObject({ field: '' })
  })
})
