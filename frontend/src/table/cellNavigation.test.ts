import { describe, expect, it } from 'vitest'
import {
  advanceFocus,
  clampToGrid,
  editKeyAction,
  focusKeyAction,
  moveFocus,
} from './cellNavigation'

describe('moveFocus', () => {
  const grid = { rows: 5, columns: 3 }

  it('bewegt sich in alle vier Richtungen', () => {
    const start = { row: 2, column: 1 }
    expect(moveFocus(start, 'ArrowUp', grid.rows, grid.columns)).toEqual({ row: 1, column: 1 })
    expect(moveFocus(start, 'ArrowDown', grid.rows, grid.columns)).toEqual({ row: 3, column: 1 })
    expect(moveFocus(start, 'ArrowLeft', grid.rows, grid.columns)).toEqual({ row: 2, column: 0 })
    expect(moveFocus(start, 'ArrowRight', grid.rows, grid.columns)).toEqual({ row: 2, column: 2 })
  })

  it('klemmt am oberen und linken Rand, statt umzuspringen', () => {
    const corner = { row: 0, column: 0 }
    expect(moveFocus(corner, 'ArrowUp', grid.rows, grid.columns)).toEqual(corner)
    expect(moveFocus(corner, 'ArrowLeft', grid.rows, grid.columns)).toEqual(corner)
  })

  it('klemmt am unteren und rechten Rand, statt umzuspringen', () => {
    const corner = { row: 4, column: 2 }
    expect(moveFocus(corner, 'ArrowDown', grid.rows, grid.columns)).toEqual(corner)
    expect(moveFocus(corner, 'ArrowRight', grid.rows, grid.columns)).toEqual(corner)
  })

  it('bleibt an Ort und Stelle, wenn das Raster leer ist', () => {
    const position = { row: 0, column: 0 }
    expect(moveFocus(position, 'ArrowDown', 0, 0)).toEqual({ row: 0, column: 0 })
  })
})

describe('advanceFocus', () => {
  it('Enter geht eine Zeile tiefer', () => {
    expect(advanceFocus({ row: 1, column: 2 }, 'down', 5, 4)).toEqual({ row: 2, column: 2 })
  })

  it('Enter bleibt in der letzten Zeile stehen', () => {
    expect(advanceFocus({ row: 4, column: 2 }, 'down', 5, 4)).toEqual({ row: 4, column: 2 })
  })

  it('Tab geht eine Spalte weiter', () => {
    expect(advanceFocus({ row: 1, column: 2 }, 'right', 5, 4)).toEqual({ row: 1, column: 3 })
  })

  it('Umschalt+Tab geht eine Spalte zurück', () => {
    expect(advanceFocus({ row: 1, column: 2 }, 'left', 5, 4)).toEqual({ row: 1, column: 1 })
  })

  it('Tab bleibt in der letzten Spalte stehen', () => {
    expect(advanceFocus({ row: 1, column: 3 }, 'right', 5, 4)).toEqual({ row: 1, column: 3 })
  })

  it('Umschalt+Tab bleibt in der ersten Spalte stehen', () => {
    expect(advanceFocus({ row: 1, column: 0 }, 'left', 5, 4)).toEqual({ row: 1, column: 0 })
  })
})

describe('clampToGrid', () => {
  it('zieht eine Position zurück, wenn das Raster geschrumpft ist', () => {
    expect(clampToGrid({ row: 9, column: 9 }, 3, 2)).toEqual({ row: 2, column: 1 })
  })

  it('lässt eine gültige Position unverändert', () => {
    expect(clampToGrid({ row: 1, column: 1 }, 3, 2)).toEqual({ row: 1, column: 1 })
  })
})

describe('focusKeyAction', () => {
  it('erkennt Pfeiltasten als Bewegung', () => {
    expect(focusKeyAction({ key: 'ArrowRight' })).toEqual({ type: 'move', direction: 'ArrowRight' })
  })

  it('erkennt Enter als Beginn der Bearbeitung', () => {
    expect(focusKeyAction({ key: 'Enter' })).toEqual({ type: 'startEdit' })
  })

  it('erkennt ein getipptes Zeichen als Beginn der Bearbeitung mit Überschreiben', () => {
    expect(focusKeyAction({ key: 'a' })).toEqual({ type: 'startEditWithChar', char: 'a' })
    expect(focusKeyAction({ key: '5' })).toEqual({ type: 'startEditWithChar', char: '5' })
  })

  it('ignoriert Tastenkombinationen mit Zusatztaste', () => {
    expect(focusKeyAction({ key: 'c', ctrlKey: true })).toBeNull()
    expect(focusKeyAction({ key: 'v', metaKey: true })).toBeNull()
    expect(focusKeyAction({ key: 'f', altKey: true })).toBeNull()
  })

  it('ignoriert Tasten ohne Bedeutung für das Raster', () => {
    expect(focusKeyAction({ key: 'Shift' })).toBeNull()
    expect(focusKeyAction({ key: 'F5' })).toBeNull()
  })
})

describe('editKeyAction', () => {
  it('Escape verwirft die Bearbeitung', () => {
    expect(editKeyAction({ key: 'Escape' })).toEqual({ type: 'cancel' })
  })

  it('Enter bestätigt und geht nach unten', () => {
    expect(editKeyAction({ key: 'Enter' })).toEqual({ type: 'commit', advance: 'down' })
  })

  it('Tab bestätigt und geht nach rechts', () => {
    expect(editKeyAction({ key: 'Tab' })).toEqual({ type: 'commit', advance: 'right' })
  })

  it('Umschalt+Tab bestätigt und geht nach links', () => {
    expect(editKeyAction({ key: 'Tab', shiftKey: true })).toEqual({ type: 'commit', advance: 'left' })
  })

  it('lässt andere Tasten für das Eingabefeld selbst übrig', () => {
    expect(editKeyAction({ key: 'a' })).toBeNull()
    expect(editKeyAction({ key: 'ArrowLeft' })).toBeNull()
  })
})
