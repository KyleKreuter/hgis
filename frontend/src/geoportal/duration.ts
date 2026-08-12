/**
 * The size warning from decision E3 (CONTRACT.md 11.1: "no hard size limit; a warning
 * from 100 000 features up"). Nothing here refuses an import -- it only estimates what
 * the user is about to wait for, so the map-extent toggle can be suggested with a real
 * number behind it instead of a vague "das kann dauern".
 */

/** One page of 10 000 objects measured 2.5 to 3.1 seconds against the live service
 *  (plan 3.2). The range is kept, not averaged, so the estimate does not claim a
 *  precision the measurement never had. */
const SECONDS_PER_PAGE_MIN = 2.5
const SECONDS_PER_PAGE_MAX = 3.1
const PAGE_SIZE = 10_000

/** From 100 000 objects up, the dialog warns (decision E3; plan 7.1). */
export const WARNING_THRESHOLD = 100_000

export function exceedsWarningThreshold(featureCount: number | null): boolean {
  return featureCount !== null && featureCount > WARNING_THRESHOLD
}

export interface DurationEstimate {
  minSeconds: number
  maxSeconds: number
}

/** Scales the measured per-page range by how many pages of `PAGE_SIZE` the count needs. */
export function estimateImportDuration(featureCount: number): DurationEstimate {
  const pages = Math.max(1, Math.ceil(featureCount / PAGE_SIZE))
  return {
    minSeconds: pages * SECONDS_PER_PAGE_MIN,
    maxSeconds: pages * SECONDS_PER_PAGE_MAX,
  }
}

/**
 * "etwa 25–31 Sekunden" below one minute, "etwa 3–4 Minuten" beyond that -- seconds stop
 * being a useful unit once the range crosses a minute. Collapses to a single number when
 * both ends round to the same one, so "etwa 3–3 Sekunden" never appears for a one-page
 * dataset, and "etwa 1 Minute" is written in the singular.
 */
export function formatDurationEstimate({ minSeconds, maxSeconds }: DurationEstimate): string {
  if (maxSeconds <= 60) {
    const min = Math.round(minSeconds)
    const max = Math.round(maxSeconds)
    return min === max ? `etwa ${min} Sekunden` : `etwa ${min}–${max} Sekunden`
  }

  const minMinutes = Math.max(1, Math.round(minSeconds / 60))
  const maxMinutes = Math.max(minMinutes, Math.round(maxSeconds / 60))
  if (minMinutes === maxMinutes) {
    return `etwa ${minMinutes} ${minMinutes === 1 ? 'Minute' : 'Minuten'}`
  }
  return `etwa ${minMinutes}–${maxMinutes} Minuten`
}
