const numberFormat = new Intl.NumberFormat('de-DE')

const relativeFormat = new Intl.RelativeTimeFormat('de-DE', { numeric: 'auto' })

const dateFormat = new Intl.DateTimeFormat('de-DE', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
})

export function formatCount(value: number): string {
  return numberFormat.format(value)
}

/**
 * Grouping separators are wrong here: 12.847 objects reads well, but the year 1900 as
 * "1.900" reads as a quantity and is simply not what the data says. An attribute value
 * is data, so only the decimal separator is localised.
 */
const attributeNumberFormat = new Intl.NumberFormat('de-DE', {
  useGrouping: false,
  maximumFractionDigits: 12,
})

export function formatAttributeNumber(value: number): string {
  return attributeNumberFormat.format(value)
}

/**
 * "vor 2 Stunden" for anything recent, a plain date beyond a week -- past that point
 * relative wording stops helping ("vor 34 Tagen" is harder to place than a date).
 */
export function formatRelative(iso: string | null): string {
  if (!iso) return 'nie geöffnet'

  const then = new Date(iso).getTime()
  // An unparseable timestamp yields NaN, and every comparison below is then false, so the
  // value would fall through to the date branch and throw a RangeError out of
  // Intl.DateTimeFormat -- taking down whatever was rendering it, a project tile among
  // others. A date nobody can read is not worth a blank page.
  if (Number.isNaN(then)) return 'unbekannt'

  const diffSeconds = Math.round((then - Date.now()) / 1000)
  const absSeconds = Math.abs(diffSeconds)

  if (absSeconds < 60) return 'gerade eben'
  if (absSeconds < 3600) return relativeFormat.format(Math.round(diffSeconds / 60), 'minute')
  if (absSeconds < 86_400) return relativeFormat.format(Math.round(diffSeconds / 3600), 'hour')
  if (absSeconds < 604_800) return relativeFormat.format(Math.round(diffSeconds / 86_400), 'day')

  return dateFormat.format(new Date(iso))
}
