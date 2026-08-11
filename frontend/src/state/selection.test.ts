import { beforeEach, describe, expect, it } from 'vitest'
import { useSelection } from './selection'

const LAYER = 'layer-a'
const OTHER = 'layer-b'

function state() {
  return useSelection.getState()
}

describe('selection store', () => {
  beforeEach(() => {
    state().clear()
  })

  it('toggles a feature on and off', () => {
    state().toggle(LAYER, 42)
    expect([...state().selected]).toEqual([42])

    state().toggle(LAYER, 42)
    expect([...state().selected]).toEqual([])
  })

  it('accumulates within one layer', () => {
    state().toggle(LAYER, 1)
    state().toggle(LAYER, 2)
    expect([...state().selected]).toEqual([1, 2])
  })

  it('drops the previous layer selection when another layer is touched', () => {
    // A fid identifies a row only within its layer: fid 1 elsewhere is a different
    // object, so carrying the set over would select something nobody pointed at.
    state().toggle(LAYER, 1)
    state().toggle(OTHER, 5)

    expect(state().layerId).toBe(OTHER)
    expect([...state().selected]).toEqual([5])
  })

  it('replaces the whole selection on select', () => {
    state().toggle(LAYER, 1)
    state().select(LAYER, [7, 8, 9])
    expect([...state().selected]).toEqual([7, 8, 9])
  })

  it('produces a new Set on every change', () => {
    // Mutating in place would leave the reference unchanged, and React would not
    // re-render -- the selection would be correct in the store and invisible on screen.
    state().toggle(LAYER, 1)
    const first = state().selected
    state().toggle(LAYER, 2)

    expect(state().selected).not.toBe(first)
    expect([...first]).toEqual([1])
  })
})

describe('select with a mode', () => {
  beforeEach(() => {
    state().clear()
  })

  it('without a mode still replaces, as before', () => {
    state().select(LAYER, [1, 2])
    state().select(LAYER, [3])
    expect([...state().selected]).toEqual([3])
  })

  it('replace mode discards the previous selection', () => {
    state().select(LAYER, [1, 2])
    state().select(LAYER, [3], 'replace')
    expect([...state().selected]).toEqual([3])
  })

  it('add mode extends the existing selection within the same layer', () => {
    state().select(LAYER, [1, 2])
    state().select(LAYER, [2, 3], 'add')
    expect([...state().selected].sort()).toEqual([1, 2, 3])
  })

  it('subtract mode removes only the given fids', () => {
    state().select(LAYER, [1, 2, 3])
    state().select(LAYER, [2], 'subtract')
    expect([...state().selected].sort()).toEqual([1, 3])
  })

  it('add mode against another layer starts fresh, like replace', () => {
    // A rectangle drawn with Shift held, right after switching layers, must not carry
    // fids from the previous layer's selection -- fid 1 there is a different object.
    state().select(LAYER, [1, 2])
    state().select(OTHER, [5], 'add')
    expect(state().layerId).toBe(OTHER)
    expect([...state().selected]).toEqual([5])
  })

  it('subtract mode against another layer removes nothing, since there is nothing shared', () => {
    state().select(LAYER, [1, 2])
    state().select(OTHER, [1], 'subtract')
    expect(state().layerId).toBe(OTHER)
    expect([...state().selected]).toEqual([])
  })
})
