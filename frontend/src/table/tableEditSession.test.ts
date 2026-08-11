import { describe, expect, it } from 'vitest'
import {
  IDLE,
  begin,
  cellValue,
  changeCount,
  end,
  hasEdit,
  reset,
  revertCell,
  setCell,
  type TableEditState,
} from './tableEditSession'

describe('begin/end', () => {
  it('startet mit leerem Puffer für den angegebenen Layer', () => {
    const state = begin('layer-1')
    expect(state).toEqual({
      layerId: 'layer-1',
      edits: new Map(),
      rowVersions: new Map(),
      active: true,
    })
  })

  it('setzt auf den Ruhezustand zurück', () => {
    expect(end()).toEqual(IDLE)
    expect(end().active).toBe(false)
  })
})

describe('setCell', () => {
  it('merkt sich eine geänderte Zelle mitsamt rowVersion', () => {
    const state = setCell(begin('layer-1'), 42, 'hoehe', 12.5, '8241', 10)
    expect(state.edits.get(42)).toEqual({ hoehe: 12.5 })
    expect(state.rowVersions.get(42)).toBe('8241')
  })

  it('behandelt NULL als gültigen, gespeicherten Wert', () => {
    const state = setCell(begin('layer-1'), 42, 'notiz', null, '8241', 'vorher')
    expect(state.edits.get(42)).toEqual({ notiz: null })
    expect(hasEdit(state, 42, 'notiz')).toBe(true)
  })

  it('zählt einen auf den Ausgangswert zurückgesetzten Wert nicht mehr als Änderung', () => {
    const edited = setCell(begin('layer-1'), 42, 'hoehe', 12.5, '8241', 10)
    const reverted = setCell(edited, 42, 'hoehe', 10, '8241', 10)
    expect(reverted.edits.has(42)).toBe(false)
    expect(reverted.rowVersions.has(42)).toBe(false)
  })

  it('lässt andere Spalten derselben Zeile unangetastet, wenn nur eine zurückgesetzt wird', () => {
    let state = begin('layer-1')
    state = setCell(state, 42, 'hoehe', 12.5, '8241', 10)
    state = setCell(state, 42, 'notiz', 'neu', '8241', 'alt')
    state = setCell(state, 42, 'hoehe', 10, '8241', 10)
    expect(state.edits.get(42)).toEqual({ notiz: 'neu' })
  })

  it('sammelt Änderungen über mehrere Zeilen unabhängig voneinander', () => {
    let state = begin('layer-1')
    state = setCell(state, 1, 'name', 'A', 'v1', 'x')
    state = setCell(state, 2, 'name', 'B', 'v2', 'y')
    expect(state.edits.size).toBe(2)
    expect(state.rowVersions).toEqual(
      new Map([
        [1, 'v1'],
        [2, 'v2'],
      ]),
    )
  })

  it('setzt NULL zurück auf NULL als keine Änderung', () => {
    const state = setCell(begin('layer-1'), 42, 'notiz', null, '8241', null)
    expect(state.edits.has(42)).toBe(false)
  })
})

describe('revertCell', () => {
  it('entfernt eine einzelne Spalte aus dem Puffer', () => {
    let state = begin('layer-1')
    state = setCell(state, 42, 'hoehe', 12.5, '8241', 10)
    state = revertCell(state, 42, 'hoehe')
    expect(state.edits.has(42)).toBe(false)
    expect(state.rowVersions.has(42)).toBe(false)
  })

  it('lässt die übrigen Spalten der Zeile stehen', () => {
    let state = begin('layer-1')
    state = setCell(state, 42, 'hoehe', 12.5, '8241', 10)
    state = setCell(state, 42, 'notiz', 'neu', '8241', 'alt')
    state = revertCell(state, 42, 'hoehe')
    expect(state.edits.get(42)).toEqual({ notiz: 'neu' })
    expect(state.rowVersions.get(42)).toBe('8241')
  })

  it('ist ein No-op für eine Zelle ohne Änderung', () => {
    const state = begin('layer-1')
    expect(revertCell(state, 42, 'hoehe')).toBe(state)
  })
})

describe('reset', () => {
  it('leert den Puffer, bleibt aber aktiv', () => {
    let state = begin('layer-1')
    state = setCell(state, 42, 'hoehe', 12.5, '8241', 10)
    const cleared = reset(state)
    expect(cleared.edits.size).toBe(0)
    expect(cleared.rowVersions.size).toBe(0)
    expect(cleared.active).toBe(true)
    expect(cleared.layerId).toBe('layer-1')
  })
})

describe('hasEdit / cellValue', () => {
  const state = setCell(begin('layer-1'), 42, 'notiz', null, '8241', 'ursprünglich')

  it('meldet eine geänderte Zelle als geändert', () => {
    expect(hasEdit(state, 42, 'notiz')).toBe(true)
  })

  it('meldet eine unveränderte Zelle als unverändert', () => {
    expect(hasEdit(state, 42, 'andere')).toBe(false)
    expect(hasEdit(state, 99, 'notiz')).toBe(false)
  })

  it('liefert den geänderten Wert, auch wenn er NULL ist', () => {
    expect(cellValue(state, 42, 'notiz', 'fallback')).toBeNull()
  })

  it('fällt auf den übergebenen Wert zurück, wenn nichts geändert wurde', () => {
    expect(cellValue(state, 42, 'andere', 'fallback')).toBe('fallback')
  })
})

describe('changeCount', () => {
  it('zählt Zellen, nicht Zeilen', () => {
    let state = begin('layer-1')
    state = setCell(state, 1, 'a', 'x', 'v1', null)
    state = setCell(state, 1, 'b', 'y', 'v1', null)
    state = setCell(state, 2, 'a', 'z', 'v2', null)
    expect(changeCount(state)).toBe(3)
  })

  it('ist null für einen leeren Puffer', () => {
    const state: TableEditState = begin('layer-1')
    expect(changeCount(state)).toBe(0)
  })
})
