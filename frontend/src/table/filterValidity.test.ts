import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/client'
import { isAmbiguousFilterFieldError, isUnknownFilterFieldError } from './filterValidity'

/** What the server really sends for a filter name that means two fields, shortened. */
const AMBIGUOUS_FILTER =
  'Mehrdeutiges Feld: kronendurchmesser. Der Name passt auf 2 Felder: ' +
  'Kronendurchmesser Quelle (Spalte kronendurchmesser, Id 019ff731-1f15-7d68-bbb4-5cc0711e86bb), ' +
  'Kronendurchmesser (Spalte kronendurchmesser_z, Id 019ff731-1f15-7e20-a2c9-77494e4fe3a0). ' +
  'Eindeutig sind: Kronendurchmesser Quelle, kronendurchmesser_z. Die Id trifft immer genau ein Feld.'

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

  /**
   * The one that keeps the filter on screen. Clearing it would take the server's message
   * with it -- and that message is the only place the user is told which names resolve.
   */
  it('does not treat an ambiguous field as a deleted one', () => {
    const error = new ApiError(400, { status: 400, detail: AMBIGUOUS_FILTER })
    expect(isUnknownFilterFieldError(error)).toBe(false)
  })
})

describe('isAmbiguousFilterFieldError', () => {
  it('recognizes the backend\'s ambiguous-field wording', () => {
    const error = new ApiError(400, { status: 400, detail: AMBIGUOUS_FILTER })
    expect(isAmbiguousFilterFieldError(error)).toBe(true)
  })

  it('stays out of the way of the other three wordings', () => {
    const wordings = [
      'Unbekanntes Feld: baujahr. Verfügbar: name, baujahr2.',
      'Unbekanntes Sortierfeld: baujahr',
      'Mehrdeutiges Sortierfeld: kronendurchmesser. Der Name passt auf 2 Felder: …',
    ]
    for (const detail of wordings) {
      expect(isAmbiguousFilterFieldError(new ApiError(400, { status: 400, detail }))).toBe(false)
    }
  })

  it('ignores the right wording on a status other than 400', () => {
    const error = new ApiError(409, { status: 409, detail: AMBIGUOUS_FILTER })
    expect(isAmbiguousFilterFieldError(error)).toBe(false)
  })

  it('ignores errors that are not an ApiError', () => {
    expect(isAmbiguousFilterFieldError(new Error('Netzwerkfehler'))).toBe(false)
    expect(isAmbiguousFilterFieldError(null)).toBe(false)
  })
})
