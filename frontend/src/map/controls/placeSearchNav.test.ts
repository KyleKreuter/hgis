import { describe, expect, test } from 'vitest'
import { moveHighlight } from './placeSearchNav'

describe('moveHighlight', () => {
  test('down from the input (-1) lands on the first row', () => {
    expect(moveHighlight(-1, 3, 'down')).toBe(0)
  })

  test('down steps through the rows in order', () => {
    expect(moveHighlight(0, 3, 'down')).toBe(1)
    expect(moveHighlight(1, 3, 'down')).toBe(2)
  })

  test('down from the last row wraps back to the input', () => {
    expect(moveHighlight(2, 3, 'down')).toBe(-1)
  })

  test('up from the input (-1) wraps to the last row', () => {
    expect(moveHighlight(-1, 3, 'up')).toBe(2)
  })

  test('up steps backwards through the rows', () => {
    expect(moveHighlight(2, 3, 'up')).toBe(1)
    expect(moveHighlight(1, 3, 'up')).toBe(0)
  })

  test('up from the first row wraps back to the input', () => {
    expect(moveHighlight(0, 3, 'up')).toBe(-1)
  })

  test('an empty list always stays on the input', () => {
    expect(moveHighlight(-1, 0, 'down')).toBe(-1)
    expect(moveHighlight(-1, 0, 'up')).toBe(-1)
  })
})
