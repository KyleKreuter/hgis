import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/client'
import { isUnknownFilterFieldError } from './filterValidity'

describe('isUnknownFilterFieldError', () => {
  it('recognizes the backend\'s unknown-field wording', () => {
    const error = new ApiError(400, {
      status: 400,
      detail: 'Unbekanntes Feld: baujahr. Verfügbar: name, baujahr2.',
    })
    expect(isUnknownFilterFieldError(error)).toBe(true)
  })

  it('does not mistake the sort error for a filter error', () => {
    // FeatureQueryService.resolveSortField reports a different message -- the sort's own
    // recovery (sortValidity.ts) handles that one, this must stay out of its way.
    const error = new ApiError(400, { status: 400, detail: 'Unbekanntes Sortierfeld: baujahr' })
    expect(isUnknownFilterFieldError(error)).toBe(false)
  })

  it('ignores a 400 with unrelated wording', () => {
    const error = new ApiError(400, { status: 400, detail: 'Ungültiger Parameter' })
    expect(isUnknownFilterFieldError(error)).toBe(false)
  })

  it('ignores the right wording on a status other than 400', () => {
    const error = new ApiError(409, { status: 409, detail: 'Unbekanntes Feld: baujahr' })
    expect(isUnknownFilterFieldError(error)).toBe(false)
  })

  it('ignores errors that are not an ApiError', () => {
    expect(isUnknownFilterFieldError(new Error('Netzwerkfehler'))).toBe(false)
    expect(isUnknownFilterFieldError(null)).toBe(false)
    expect(isUnknownFilterFieldError(undefined)).toBe(false)
  })
})
