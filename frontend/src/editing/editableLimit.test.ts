import { describe, expect, it } from 'vitest'
import { MAX_EDITABLE, SERVER_PAGE_LIMIT } from './editableLimit'

describe('editable limit', () => {
  /**
   * The one thing that can go wrong here, and did: someone raises the editor's limit for a
   * good local reason and every draw session starts answering 400, because the number goes
   * to the server as `size` and the server refuses anything above its ceiling. Raising it
   * needs the server's ceiling raised first -- this test is where that gets noticed.
   */
  it('never asks the server for more than one page', () => {
    expect(MAX_EDITABLE).toBeLessThanOrEqual(SERVER_PAGE_LIMIT)
  })

  it('is a whole number of features, and at least one', () => {
    expect(Number.isInteger(MAX_EDITABLE)).toBe(true)
    expect(MAX_EDITABLE).toBeGreaterThan(0)
  })
})
