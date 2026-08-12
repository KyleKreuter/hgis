import { describe, expect, it } from 'vitest'
import { editDraft, initialDraftSync, liftDraft, reconcileDraftValue } from './filterDraft'

describe('reconcileDraftValue', () => {
  it('adopts an external value change outright', () => {
    const state = initialDraftSync('')
    const next = reconcileDraftValue(state, 'baujahr > 1990')
    expect(next).toEqual({ draft: 'baujahr > 1990', lifted: 'baujahr > 1990' })
  })

  it('leaves an in-progress edit alone when value merely catches up to what was lifted', () => {
    const lifted = liftDraft('Schmidt')
    // The parent has now processed the lift and echoes it back as `value`.
    const next = reconcileDraftValue(lifted, 'Schmidt')
    expect(next).toBe(lifted)
  })

  it('does not touch a fresh edit that has not been lifted yet, even if value happens to match', () => {
    // `value` still lags behind the very first keystroke -- nothing to reconcile.
    const typed = editDraft(initialDraftSync(''), 'S')
    const next = reconcileDraftValue(typed, '')
    expect(next).toBe(typed)
  })
})

describe('editDraft / liftDraft', () => {
  it('editDraft changes the draft without marking it as lifted', () => {
    const state = editDraft(initialDraftSync('old'), 'new')
    expect(state).toEqual({ draft: 'new', lifted: 'old' })
  })

  it('liftDraft marks the draft as this component\'s own doing', () => {
    const state = liftDraft('new')
    expect(state).toEqual({ draft: 'new', lifted: 'new' })
  })
})

describe('the restored-filter race (CONTRACT.md phase 17)', () => {
  it('a value restored after mount replaces an untouched empty draft', () => {
    // FilterBar mounts before the working state has loaded -- `value` is empty.
    let state = initialDraftSync('')
    // The saved filter arrives and AttributeTable passes it down as the new `value`.
    state = reconcileDraftValue(state, 'baujahr > 1990')
    expect(state.draft).toBe('baujahr > 1990')
    // Had this not run, `draft` would still be '' here -- and FilterBar's own debounce
    // effect (`draft !== value` -> lift `draft`) would then fire and overwrite the
    // restored value with the empty string it started with.
    expect(state.draft).toBe(state.lifted)
  })

  it('a value restored mid-edit still wins over what is being typed', () => {
    let state = editDraft(initialDraftSync(''), 'Schm')
    state = reconcileDraftValue(state, 'baujahr > 1990')
    expect(state).toEqual({ draft: 'baujahr > 1990', lifted: 'baujahr > 1990' })
  })

  it('reset back to nothing (CONTRACT.md rule 1\'s "Filter löschen") is itself an external change', () => {
    let state = liftDraft('baujahr > 1990')
    // AttributeTable's `resetRestoredQuery` writes `text` back to '' from outside.
    state = reconcileDraftValue(state, '')
    expect(state).toEqual({ draft: '', lifted: '' })
  })
})
