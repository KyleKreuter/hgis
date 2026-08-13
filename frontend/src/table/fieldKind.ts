/**
 * Maps a PostgreSQL `data_type` to the input it deserves, and back to the string an
 * HTML input wants for its `value`.
 *
 * Extracted out of `editing/AttributeForm.tsx` so the table's cell editor and the
 * attribute form share one mapping instead of two that could drift apart. Belongs to
 * the table module because that is where the second consumer lives; the form imports
 * it from here.
 *
 * The wire format is settled by `FeaturePropertyWireFormatTest` on the backend -- see
 * CONTRACT.md. All eleven types round-trip through GET/POST unchanged.
 */

import { fromDateTimeLocalInput, toDateTimeLocalInput } from './timestampValue'

export type FieldKind =
  | 'text'
  | 'integer'
  | 'decimal'
  | 'boolean'
  | 'date'
  | 'time'
  | 'timestamp'
  | 'readonly'

export function kindOf(dataType: string): FieldKind {
  const type = dataType.toLowerCase()
  if (type.startsWith('timestamp')) return 'timestamp'
  if (type === 'date') return 'date'
  if (type === 'time') return 'time'
  if (type === 'boolean') return 'boolean'
  // A base64 blob typed by hand is a fixture for mistakes, not a way to edit data --
  // uuid and bytea are shown but never opened for editing (user decision, CONTRACT.md).
  if (type === 'uuid' || type === 'bytea') return 'readonly'
  if (/^(smallint|integer|bigint)$/.test(type)) return 'integer'
  if (/^(double precision|numeric|real|decimal)/.test(type)) return 'decimal'
  return 'text'
}

/**
 * `datetime-local` holds a wall clock in the browser's own zone; the API delivers an
 * instant in UTC. The two need converting into one another, not trimming: cutting the
 * ISO text to sixteen characters showed the UTC hour in a field that means local time --
 * an hour off in Berlin, two in summer -- and dropped the seconds along the way. See
 * `timestampValue.ts`; `toWireValue` is the way back.
 *
 * `date` and `time` already arrive in exactly the shape their input wants -- the old
 * ten-character trim here worked around a timestamp bug in `date` that no longer
 * exists (CONTRACT.md).
 */
export function toInputValue(value: unknown, kind: FieldKind): string {
  const text = String(value)
  if (kind === 'timestamp') return toDateTimeLocalInput(text)
  return text
}

/**
 * What the API is sent for a value the user typed into the input of `kind`.
 *
 * Only `timestamp` is not already in the wire format: the server parses it as
 * `ISO_OFFSET_DATE_TIME` and answers a value without an offset with 400 -- which fails
 * the whole batch, because one save is one transaction, so a single timestamp took every
 * other pending change down with it.
 */
export function toWireValue(text: string, kind: FieldKind): string {
  if (kind === 'timestamp') return fromDateTimeLocalInput(text)
  return text
}

/**
 * The draft a cell starts with when a bare character key opened it, spreadsheet style.
 *
 * A numeric column has to turn that character into a number right here. The draft is
 * saved as it stands if the user presses Enter without typing anything further, and the
 * server rejects the text "6" for an integer column with a message that reads like a
 * contradiction: "Feld Zustand erwartet den Typ integer. Erhalten: 6". Typing into an
 * already-open cell never had the problem, because `FieldInput` converts on every
 * keystroke -- only this one entry path skipped it.
 *
 * A character that is no number on its own ("-", ".", "e") stays text: it is the start
 * of a number still being typed, and `Number('-')` would make it NaN, which is worse
 * than leaving it for the next keystroke to replace.
 */
export function initialDraftFromChar(char: string, kind: FieldKind): unknown {
  if (kind !== 'integer' && kind !== 'decimal') return char
  const value = Number(char)
  return Number.isNaN(value) ? char : value
}
