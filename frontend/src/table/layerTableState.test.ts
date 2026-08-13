import { describe, expect, it } from 'vitest'
import type { LayerViewState } from '@/state/viewState'
import { DEFAULT_FILTER_MODE, layerTableStateOf } from './layerTableState'

const NOTHING_SAVED: LayerViewState = { sort: null, query: null, selection: [] }

const SAVED: LayerViewState = {
  sort: { field: 'strasse', desc: true },
  query: { mode: 'filter', text: "strasse = 'Alt'" },
  selection: [1, 2],
}

describe('layerTableStateOf', () => {
  it('übernimmt Sortierung und Suchbegriff eines Layers, für den etwas gespeichert ist', () => {
    expect(layerTableStateOf(SAVED, true)).toEqual({
      sort: { field: 'strasse', desc: true },
      mode: 'filter',
      text: "strasse = 'Alt'",
      restoredQuery: true,
    })
  })

  it('setzt für einen Layer ohne gespeicherten Stand alles auf den Ausgangswert zurück', () => {
    // Der belegte Fehler: der Wiederherstellungs-Effekt setzte nur, *wenn* der neue Layer
    // etwas Gespeichertes hatte. Sonst erbte er den Zustand des vorigen -- in Layer A nach
    // "aaa" suchen, auf B wechseln, und B zeigte "0 / 0" für 1.000 Objekte.
    expect(layerTableStateOf(NOTHING_SAVED, true)).toEqual({
      sort: null,
      mode: DEFAULT_FILTER_MODE,
      text: '',
      restoredQuery: false,
    })
  })

  it('setzt eine geerbte Sortierung auch dann zurück, wenn ein Suchbegriff gespeichert ist', () => {
    expect(layerTableStateOf({ ...SAVED, sort: null }, true).sort).toBeNull()
  })

  it('setzt einen geerbten Suchbegriff auch dann zurück, wenn eine Sortierung gespeichert ist', () => {
    const state = layerTableStateOf({ ...SAVED, query: null }, true)

    expect(state.text).toBe('')
    expect(state.mode).toBe(DEFAULT_FILTER_MODE)
    expect(state.sort).toEqual({ field: 'strasse', desc: true })
  })

  it('meldet beim zweiten Besuch keinen wiederhergestellten Suchbegriff mehr', () => {
    // Der Hinweisbalken ist ein einmaliger Hinweis je Layer und Sitzung: wer den Filter
    // gerade selbst gesetzt hat, weiß, dass er aktiv ist.
    const state = layerTableStateOf(SAVED, false)

    expect(state.restoredQuery).toBe(false)
    expect(state.text).toBe("strasse = 'Alt'")
  })

  it('meldet ohne gespeicherten Suchbegriff nie einen wiederhergestellten', () => {
    expect(layerTableStateOf(NOTHING_SAVED, true).restoredQuery).toBe(false)
    expect(layerTableStateOf(NOTHING_SAVED, false).restoredQuery).toBe(false)
  })
})
