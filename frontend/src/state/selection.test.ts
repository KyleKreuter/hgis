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
