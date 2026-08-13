/**
 * Converts between the wire format of a `timestamptz` column and the text a
 * `datetime-local` input reads and writes.
 *
 * The two are not the same string, and treating them as one is what broke saving a
 * timestamp entirely. The API sends and expects an instant: it prints
 * `"2024-03-01T08:15:30.000Z"` and parses with `ISO_OFFSET_DATE_TIME`, so a value
 * without an offset is answered with 400 (CONTRACT.md, `FeaturePropertyWireFormatTest`).
 * `datetime-local` on the other hand has no notion of a zone at all -- its value is a
 * wall clock, always read and written in the browser's own zone. Handing the UTC text to
 * the input therefore showed the wrong hour, and handing the input's text back to the
 * API rejected the whole edit batch.
 *
 * The zone belongs on the client, so this module is where it is applied -- once, for
 * both directions, and for both editors that share `FieldInput`.
 *
 * `timeZone` is a seam for the tests: production always omits it and gets the browser's
 * zone. Without it every assertion here would have to be written in whatever zone the
 * machine running the tests happens to be in, and on a UTC machine -- the usual CI
 * setup -- a conversion that does nothing at all would pass every one of them.
 */

/** `YYYY-MM-DDTHH:mm` with optional seconds: what a `datetime-local` input holds. */
const DATE_TIME_LOCAL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/

interface WallClock {
  year: number
  month: number
  day: number
  hour: number
  minute: number
  second: number
}

function pad(value: number, length = 2): string {
  return String(value).padStart(length, '0')
}

/** The wall clock an instant shows in `timeZone`, or in the browser's zone without one. */
function wallClockIn(instant: Date, timeZone: string | undefined): WallClock {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    // 'h23' rather than `hour12: false`: the latter reports midnight as hour 24 in some
    // engines, which then reads as the wrong day.
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(instant)

  const value = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find((part) => part.type === type)?.value ?? '0')

  return {
    year: value('year'),
    month: value('month'),
    day: value('day'),
    hour: value('hour'),
    minute: value('minute'),
    second: value('second'),
  }
}

/** How far `timeZone` runs ahead of UTC at this instant, in minutes (Berlin: 60 or 120). */
function offsetMinutesAt(instant: Date, timeZone: string | undefined): number {
  const clock = wallClockIn(instant, timeZone)
  const asIfUtc = Date.UTC(
    clock.year,
    clock.month - 1,
    clock.day,
    clock.hour,
    clock.minute,
    clock.second,
  )
  // Rounded rather than divided exactly, as a guard and not because anything here needs
  // it: both callers below build their instant from a wall clock, so it always lands on a
  // whole second and the division comes out whole. An instant carrying milliseconds would
  // leave a remainder -- 999ms is a sixtieth of a minute, far short of the half that could
  // change the result -- and so would a zone whose offset is not a whole number of
  // minutes, which no zone this runtime reports is.
  return Math.round((asIfUtc - instant.getTime()) / 60_000)
}

/**
 * The instant a `datetime-local` value stands for, as a text the API accepts.
 *
 * Seconds are optional in the input and default to zero, exactly as the browser reads
 * them. Anything that is not a `datetime-local` value is passed through untouched rather
 * than turned into a wrong date -- the server's own error message is more use than a
 * silently invented timestamp.
 */
export function fromDateTimeLocalInput(value: string, timeZone?: string): string {
  const match = DATE_TIME_LOCAL.exec(value)
  if (!match) return value

  const [, year, month, day, hour, minute, second = '00'] = match
  const wallAsIfUtc = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second),
  )

  // Two passes, because the offset is itself a function of the instant we are looking
  // for. The first guess uses the offset at the wall clock read as UTC; on the two days
  // a year the zone changes, that guess can land on the wrong side of the change, and
  // asking again at the corrected instant settles it.
  let offset = offsetMinutesAt(new Date(wallAsIfUtc), timeZone)
  offset = offsetMinutesAt(new Date(wallAsIfUtc - offset * 60_000), timeZone)

  const sign = offset < 0 ? '-' : '+'
  const absolute = Math.abs(offset)
  // `toISOString` of the shifted instant prints the wall clock we started from, which is
  // what an offset timestamp states before its offset.
  const wallClock = new Date(wallAsIfUtc).toISOString().slice(0, 19)
  return `${wallClock}${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
}

/**
 * The same instant as a `datetime-local` value, in local time and with seconds.
 *
 * Seconds are kept rather than trimmed away: the column stores them, and an editor that
 * drops them would zero the seconds of every row it is opened on.
 *
 * A value the browser cannot read as an instant is passed through unchanged, so an
 * unexpected wire format shows up as itself instead of as "Invalid Date".
 */
export function toDateTimeLocalInput(value: string, timeZone?: string): string {
  const instant = new Date(value)
  if (Number.isNaN(instant.getTime())) return value

  const clock = wallClockIn(instant, timeZone)
  return (
    `${pad(clock.year, 4)}-${pad(clock.month)}-${pad(clock.day)}` +
    `T${pad(clock.hour)}:${pad(clock.minute)}:${pad(clock.second)}`
  )
}
