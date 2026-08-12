import { describe, expect, it } from 'vitest'
import { needsSelectAllConfirmation, SELECT_ALL_MATCHES_CONFIRM_THRESHOLD } from './selectAllMatches'

describe('needsSelectAllConfirmation', () => {
  it('does not ask up to and including the threshold', () => {
    expect(needsSelectAllConfirmation(SELECT_ALL_MATCHES_CONFIRM_THRESHOLD)).toBe(false)
  })

  it('asks above the threshold', () => {
    expect(needsSelectAllConfirmation(SELECT_ALL_MATCHES_CONFIRM_THRESHOLD + 1)).toBe(true)
  })

  it('does not ask for a small count', () => {
    expect(needsSelectAllConfirmation(3)).toBe(false)
  })

  it('does not ask for zero matches', () => {
    expect(needsSelectAllConfirmation(0)).toBe(false)
  })
})
