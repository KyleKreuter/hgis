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
 * "vor 2 Stunden" for anything recent, a plain date beyond a week -- past that point
 * relative wording stops helping ("vor 34 Tagen" is harder to place than a date).
 */
export function formatRelative(iso: string | null): string {
  if (!iso) return 'nie geöffnet'

  const then = new Date(iso).getTime()
  const diffSeconds = Math.round((then - Date.now()) / 1000)
  const absSeconds = Math.abs(diffSeconds)

  if (absSeconds < 60) return 'gerade eben'
  if (absSeconds < 3600) return relativeFormat.format(Math.round(diffSeconds / 60), 'minute')
  if (absSeconds < 86_400) return relativeFormat.format(Math.round(diffSeconds / 3600), 'hour')
  if (absSeconds < 604_800) return relativeFormat.format(Math.round(diffSeconds / 86_400), 'day')

  return dateFormat.format(new Date(iso))
}
