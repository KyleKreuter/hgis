import { afterEach, describe, expect, test, vi } from 'vitest'
import { formatAttributeNumber, formatCount, formatRelative } from './format'

/**
 * These three decide how numbers and timestamps read across the whole application, and
 * two of them differ deliberately: a count is grouped, an attribute value is not.
 *
 * German locale output contains non-breaking spaces and a narrow no-break space as the
 * grouping separator, so the assertions normalise whitespace rather than pasting
 * invisible characters into the expected strings.
 */
function normalise(value: string): string {
  return value.replace(/[  ]/g, ' ')
}

afterEach(() => {
  vi.useRealTimers()
})

describe('formatCount', () => {
  test('groups thousands', () => {
    expect(formatCount(12_847)).toBe('12.847')
    expect(formatCount(0)).toBe('0')
  })
})

describe('formatAttributeNumber', () => {
  test('does not group -- a year is data, not a quantity', () => {
    expect(formatAttributeNumber(1900)).toBe('1900')
    expect(formatAttributeNumber(12_847)).toBe('12847')
  })

  test('localises the decimal separator and keeps the precision coordinates need', () => {
    expect(formatAttributeNumber(1.5)).toBe('1,5')
    expect(formatAttributeNumber(53.550341)).toBe('53,550341')
  })
})

describe('formatRelative', () => {
  /** Fixes "now" so the relative wording is decided by the input, not by the clock. */
  function at(now: string) {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(now))
  }

  test('names the empty case rather than showing a date', () => {
    expect(formatRelative(null)).toBe('nie geöffnet')
  })

  test('reads as relative wording up to a week', () => {
    at('2026-08-12T12:00:00Z')
    expect(formatRelative('2026-08-12T11:59:30Z')).toBe('gerade eben')
    expect(normalise(formatRelative('2026-08-12T11:30:00Z'))).toBe('vor 30 Minuten')
    expect(normalise(formatRelative('2026-08-12T10:00:00Z'))).toBe('vor 2 Stunden')
    expect(normalise(formatRelative('2026-08-10T12:00:00Z'))).toBe('vorgestern')
  })

  test('switches to a plain date beyond a week', () => {
    at('2026-08-12T12:00:00Z')
    // "vor 34 Tagen" is harder to place than the date itself.
    expect(formatRelative('2026-07-09T12:00:00Z')).toBe('09.07.2026')
  })

  /*
   * Known defect, not a rule worth keeping: an unparseable timestamp reaches
   * `Intl.DateTimeFormat.format` as an Invalid Date and throws a RangeError, which takes
   * down whatever was rendering the project tile. Every comparison above it is against
   * NaN and therefore false, so the bad value always falls through to the date branch.
   * `format.ts` is not this package's to change -- the test records the behaviour so the
   * fix is visible as a red test here.
   */
  test('throws on an unparseable timestamp (known defect)', () => {
    expect(() => formatRelative('nicht-ein-datum')).toThrow(RangeError)
  })
})
