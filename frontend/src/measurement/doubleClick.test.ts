import { describe, expect, it } from 'vitest'
import {
  DOUBLE_CLICK_MS,
  isSecondClick,
  wasSecondClickPlaced,
  type ClickRecord,
} from './doubleClick'

function click(x: number, y: number, time: number, placed = true): ClickRecord {
  return { x, y, time, placed }
}

describe('isSecondClick', () => {
  it('erkennt den zweiten Klick eines Doppelklicks trotz Handzitterns', () => {
    expect(isSecondClick(click(100, 100, 1000), { x: 102, y: 101, time: 1080 })).toBe(true)
  })

  it('lässt einen bewusst gesetzten zweiten Punkt durch', () => {
    // Dieselbe Stelle, aber deutlich später: ein Punkt, kein Doppelklick.
    expect(
      isSecondClick(click(100, 100, 1000), { x: 100, y: 100, time: 1000 + DOUBLE_CLICK_MS + 1 }),
    ).toBe(false)
    // Und schnell, aber anderswo: eine gezogene Linie, kein Doppelklick.
    expect(isSecondClick(click(100, 100, 1000), { x: 140, y: 100, time: 1050 })).toBe(false)
  })

  it('hat ohne Vorgänger nichts zu vergleichen -- etwa nach einem Moduswechsel', () => {
    expect(isSecondClick(null, { x: 100, y: 100, time: 1000 })).toBe(false)
  })
})

describe('wasSecondClickPlaced', () => {
  /**
   * Der träge Doppelklick: sein zweiter Klick liegt außerhalb des engen Fensters, ist
   * also als Punkt gesetzt worden. Beim Doppelklick selbst steht fest, dass er keiner
   * war -- und genau dann wird er zurückgenommen.
   */
  it('meldet den Punkt, den ein träger Doppelklick gesetzt hat', () => {
    expect(wasSecondClickPlaced(click(100, 100, 1000), { x: 101, y: 100 })).toBe(true)
  })

  it('meldet nichts, wenn der zweite Klick schon unterdrückt wurde', () => {
    expect(wasSecondClickPlaced(click(100, 100, 1000, false), { x: 100, y: 100 })).toBe(false)
  })

  it('meldet nichts für einen Doppelklick anderswo', () => {
    expect(wasSecondClickPlaced(click(100, 100, 1000), { x: 200, y: 180 })).toBe(false)
    expect(wasSecondClickPlaced(null, { x: 100, y: 100 })).toBe(false)
  })
})
