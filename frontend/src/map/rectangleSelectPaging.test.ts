import { describe, expect, it, vi } from 'vitest'
import {
  collectAllFids,
  exceedsMaximum,
  needsConfirmation,
  RECTANGLE_SELECT_CONFIRM_THRESHOLD,
  RECTANGLE_SELECT_MAX,
} from './rectangleSelectPaging'

describe('needsConfirmation', () => {
  it('fragt bis einschließlich der Schwelle nicht nach', () => {
    expect(needsConfirmation(RECTANGLE_SELECT_CONFIRM_THRESHOLD)).toBe(false)
  })

  it('fragt oberhalb der Schwelle nach', () => {
    expect(needsConfirmation(RECTANGLE_SELECT_CONFIRM_THRESHOLD + 1)).toBe(true)
  })
})

describe('exceedsMaximum', () => {
  it('erlaubt genau die Obergrenze', () => {
    expect(exceedsMaximum(RECTANGLE_SELECT_MAX)).toBe(false)
  })

  it('lehnt oberhalb der Obergrenze ab', () => {
    expect(exceedsMaximum(RECTANGLE_SELECT_MAX + 1)).toBe(true)
  })
})

describe('collectAllFids', () => {
  it('folgt dem Cursor bis zur letzten Seite', async () => {
    const fetchPage = vi
      .fn()
      .mockResolvedValueOnce({ features: [{ fid: 1 }, { fid: 2 }], nextCursor: 'a' })
      .mockResolvedValueOnce({ features: [{ fid: 3 }], nextCursor: null })

    const result = await collectAllFids(fetchPage)

    expect(result).toEqual({ fids: [1, 2, 3], truncated: false })
    expect(fetchPage).toHaveBeenNthCalledWith(1, undefined)
    expect(fetchPage).toHaveBeenNthCalledWith(2, 'a')
  })

  it('bricht ab, sobald mehr als max Objekte angekommen sind, und meldet truncated', async () => {
    const fetchPage = vi
      .fn()
      .mockResolvedValueOnce({ features: [{ fid: 1 }, { fid: 2 }, { fid: 3 }], nextCursor: 'a' })

    const result = await collectAllFids(fetchPage, 2)

    expect(result).toEqual({ fids: [1, 2, 3], truncated: true })
    expect(fetchPage).toHaveBeenCalledTimes(1)
  })

  it('liefert ein leeres Ergebnis für eine leere erste Seite', async () => {
    const fetchPage = vi.fn().mockResolvedValueOnce({ features: [], nextCursor: null })
    expect(await collectAllFids(fetchPage)).toEqual({ fids: [], truncated: false })
  })

  it('behandelt ein fehlendes nextCursor wie null', async () => {
    const fetchPage = vi.fn().mockResolvedValueOnce({ features: [{ fid: 1 }] })
    expect(await collectAllFids(fetchPage)).toEqual({ fids: [1], truncated: false })
  })
})
