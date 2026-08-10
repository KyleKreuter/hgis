import { describe, expect, it } from 'vitest'
import { isNoOpMove, moveItem, reorderedIdsBottomToTop } from './reorder'

describe('moveItem', () => {
  const items = ['a', 'b', 'c', 'd']

  it('moves an entry upwards', () => {
    expect(moveItem(items, 2, 0)).toEqual(['c', 'a', 'b', 'd'])
  })

  it('moves an entry downwards', () => {
    // Insertion point 3 sits between 'c' and 'd'. Removing 'a' first shifts everything
    // up by one, so without the correction this would land after 'd'.
    expect(moveItem(items, 0, 3)).toEqual(['b', 'c', 'a', 'd'])
  })

  it('moves an entry to the very bottom', () => {
    expect(moveItem(items, 0, items.length)).toEqual(['b', 'c', 'd', 'a'])
  })

  it('moves an entry to the very top', () => {
    expect(moveItem(items, 3, 0)).toEqual(['d', 'a', 'b', 'c'])
  })

  it('leaves the list untouched when dropped on its own edges', () => {
    expect(moveItem(items, 1, 1)).toEqual(items)
    expect(moveItem(items, 1, 2)).toEqual(items)
  })

  it('does not mutate its input', () => {
    const original = [...items]
    moveItem(items, 0, 3)
    expect(items).toEqual(original)
  })
})

describe('reorderedIdsBottomToTop', () => {
  // As the tree displays them: first entry is on top of the map.
  const displayed = [{ id: 'top' }, { id: 'middle' }, { id: 'bottom' }]

  it('reverses the tree order for the API', () => {
    // No move, just the direction flip.
    expect(reorderedIdsBottomToTop(displayed, 0, 0)).toEqual(['bottom', 'middle', 'top'])
  })

  it('sends the moved layer at its new depth', () => {
    // Drag "bottom" to the very top of the tree -> it must arrive last, i.e. highest zIndex.
    expect(reorderedIdsBottomToTop(displayed, 2, 0)).toEqual(['middle', 'top', 'bottom'])
  })

  it('puts a layer dragged to the tree bottom at zIndex 0', () => {
    expect(reorderedIdsBottomToTop(displayed, 0, 3)).toEqual(['top', 'bottom', 'middle'])
  })
})

describe('isNoOpMove', () => {
  it('detects both edges of the dragged row itself', () => {
    expect(isNoOpMove(2, 2)).toBe(true)
    expect(isNoOpMove(2, 3)).toBe(true)
  })

  it('detects a row that is not in the list', () => {
    expect(isNoOpMove(-1, 0)).toBe(true)
  })

  it('accepts a real move', () => {
    expect(isNoOpMove(2, 0)).toBe(false)
    expect(isNoOpMove(0, 2)).toBe(false)
  })
})
