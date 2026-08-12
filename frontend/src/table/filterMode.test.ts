import { describe, expect, it } from 'vitest'
import { toggleFilterMode } from './filterMode'

describe('toggleFilterMode', () => {
  it('switches from search to filter', () => {
    expect(toggleFilterMode('search')).toBe('filter')
  })

  it('switches from filter to search', () => {
    expect(toggleFilterMode('filter')).toBe('search')
  })

  it('round-trips back to the starting mode', () => {
    expect(toggleFilterMode(toggleFilterMode('search'))).toBe('search')
    expect(toggleFilterMode(toggleFilterMode('filter'))).toBe('filter')
  })
})
