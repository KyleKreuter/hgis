import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/client'
import { isUnknownSortFieldError } from './sortValidity'

describe('isUnknownSortFieldError', () => {
  it('recognizes the backend\'s unknown-sort-field wording', () => {
    const error = new ApiError(400, { status: 400, detail: 'Unbekanntes Sortierfeld: baujahr' })
    expect(isUnknownSortFieldError(error)).toBe(true)
  })

  it('does not mistake a filter error for an unknown field with the sort error', () => {
    // FilterParser reports an unknown field under a different message -- the filter
    // stays untouched, so this must not trigger the sort reset.
    const error = new ApiError(400, { status: 400, detail: 'Unbekanntes Feld: baujahr' })
    expect(isUnknownSortFieldError(error)).toBe(false)
  })

  it('ignores a 400 with unrelated wording', () => {
    const error = new ApiError(400, { status: 400, detail: 'Ungültiger Parameter' })
    expect(isUnknownSortFieldError(error)).toBe(false)
  })

  it('ignores the right wording on a status other than 400', () => {
    const error = new ApiError(409, { status: 409, detail: 'Unbekanntes Sortierfeld: baujahr' })
    expect(isUnknownSortFieldError(error)).toBe(false)
  })

  it('ignores errors that are not an ApiError', () => {
    expect(isUnknownSortFieldError(new Error('Netzwerkfehler'))).toBe(false)
    expect(isUnknownSortFieldError(null)).toBe(false)
    expect(isUnknownSortFieldError(undefined)).toBe(false)
  })
})
