/**
 * Wording of an import preview (plan sections A.3 and A.7).
 *
 * Kept apart from the dialog because this is where the preview earns its keep: a sample
 * value reading "MÃ¼llerstraÃŸe" and a location of "53,5° N / 9,9° O" are what let the
 * user see a wrong encoding or a wrong CRS while nothing has been written yet. Pure
 * functions, so the two decisions that are easy to get wrong -- the hemisphere sign and
 * the states that print as nothing -- are covered by tests.
 */

import type { CrsConfidence } from '@/api/imports'
import { formatCount } from '@/lib/format'

/**
 * One decimal place, which is roughly a city.
 *
 * The location answers "is this the right corner of the world", not "where exactly" --
 * and more places would suggest an accuracy the centre of a bounding box does not have.
 */
const degreeFormat = new Intl.NumberFormat('de-DE', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})

/**
 * Where the data lands, in plain language: "53,5° N / 9,9° O".
 *
 * Taken from the centre of the extent, which the server always reports in WGS 84 no
 * matter the source CRS. Null when there is nothing to locate -- an empty file, or
 * coordinates the server could not place.
 */
export function formatLocation(extent: [number, number, number, number] | null): string | null {
  if (!extent || !extent.every(Number.isFinite)) return null

  const [minLng, minLat, maxLng, maxLat] = extent
  const latitude = degrees((minLat + maxLat) / 2, 'N', 'S')
  const longitude = degrees((minLng + maxLng) / 2, 'O', 'W')
  return `${latitude} / ${longitude}`
}

function degrees(value: number, positive: string, negative: string): string {
  // Math.abs keeps the sign out of the number; the hemisphere letter carries it.
  return `${degreeFormat.format(Math.abs(value))}° ${value < 0 ? negative : positive}`
}

export interface SampleText {
  text: string
  /** True when the text stands in for a value that has nothing printable to show. */
  placeholder: boolean
}

/**
 * How a single sample value is written out.
 *
 * NULL, an empty string and a run of blanks all look like nothing in a list of samples,
 * yet they are three different states -- and telling NULL from "" apart is a rule the
 * attribute table already follows (plan section D.4). Blanks get their own marker
 * because fixed-width DBF fields pad with them, which is worth seeing. Everything with
 * content is printed verbatim: any cleanup here would hide the very mangling the
 * preview exists to expose.
 */
export function formatSample(value: string | null): SampleText {
  if (value === null) return { text: 'NULL', placeholder: true }
  if (value === '') return { text: 'leer', placeholder: true }
  if (value.trim() === '') return { text: 'nur Leerzeichen', placeholder: true }
  return { text: value, placeholder: false }
}

/** "1.003 Objekte", or a plain statement of ignorance where the format has no count. */
export function formatFeatureCount(count: number | null): string {
  return count === null ? 'Anzahl unbekannt' : `${formatCount(count)} Objekte`
}

/** The encoding in use, or why there is none to choose. */
export function formatCharset(charset: string | null): string {
  return charset ?? 'Kodierung vom Format vorgegeben'
}

/** Short enough to sit inline behind the EPSG code. */
export const CRS_CONFIDENCE_LABELS: Record<CrsConfidence, string> = {
  DECLARED: 'aus der Datei',
  ASSUMED: 'angenommen',
  GUESSED: 'geraten',
}
