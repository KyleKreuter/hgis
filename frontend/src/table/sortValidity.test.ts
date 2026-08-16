import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/client'
import { isAmbiguousSortFieldError, isUnknownSortFieldError } from './sortValidity'

/** What the server really sends for a name that means two fields, shortened. */
const AMBIGUOUS_SORT =
  'Mehrdeutiges Sortierfeld: kronendurchmesser. Der Name passt auf 2 Felder: ' +
  'Kronendurchmesser Quelle (Spalte kronendurchmesser, Id 019ff731-1f0c-7de5-9100-b9022e19ea3f), ' +
  'Kronendurchmesser (Spalte kronendurchmesser_z, Id 019ff731-1f0c-7de5-9100-b9022e19ea40). ' +
  'Eindeutig sind: Kronendurchmesser Quelle, kronendurchmesser_z. Die Id trifft immer genau ein Feld.'

describe('isUnknownSortFieldError', () => {
  it('recognizes the backend\'s unknown-sort-field wording', () => {
    const error = new ApiError(400, { status: 400, detail: 'Unbekanntes Sortierfeld: baujahr' })
    expect(isUnknownSortFieldError(error)).toBe(true)
  })

  it('also recognizes a sort field that means two fields at once', () => {
    // Not a fix, a recovery: the table falls back to unsorted instead of sitting on a 400
    // it cannot classify. See the note in sortValidity.ts.
    const error = new ApiError(400, { status: 400, detail: AMBIGUOUS_SORT })
    expect(isUnknownSortFieldError(error)).toBe(true)
  })

  it('does not mistake a filter error for an unknown field with the sort error', () => {
    // FilterParser reports an unknown field under a different message -- the filter
    // stays untouched, so this must not trigger the sort reset.
    const error = new ApiError(400, { status: 400, detail: 'Unbekanntes Feld: baujahr' })
    expect(isUnknownSortFieldError(error)).toBe(false)
  })

  it('does not mistake an ambiguous filter field for the sort error', () => {
    // Same disjointness, for the other new wording: "Mehrdeutiges Feld" is the filter's,
    // and an ambiguous filter expression stays visible so the user can name the field.
    const error = new ApiError(400, {
      status: 400,
      detail: 'Mehrdeutiges Feld: kronendurchmesser. Der Name passt auf 2 Felder: …',
    })
    expect(isUnknownSortFieldError(error)).toBe(false)
    expect(isAmbiguousSortFieldError(error)).toBe(false)
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

describe('isAmbiguousSortFieldError', () => {
  it('recognizes only the ambiguous half', () => {
    const ambiguous = new ApiError(400, { status: 400, detail: AMBIGUOUS_SORT })
    const unknown = new ApiError(400, { status: 400, detail: 'Unbekanntes Sortierfeld: baujahr' })

    expect(isAmbiguousSortFieldError(ambiguous)).toBe(true)
    expect(isAmbiguousSortFieldError(unknown))
      .toBe(false)
  })

  it('ignores the right wording on a status other than 400', () => {
    const error = new ApiError(409, { status: 409, detail: AMBIGUOUS_SORT })
    expect(isAmbiguousSortFieldError(error)).toBe(false)
  })
})
