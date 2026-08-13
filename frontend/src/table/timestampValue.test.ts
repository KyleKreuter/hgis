import { describe, expect, it } from 'vitest'
import { fromDateTimeLocalInput, toDateTimeLocalInput } from './timestampValue'

/**
 * Every assertion names its zone explicitly. A test that relied on the machine's own
 * zone would pass on a UTC runner even for a conversion that does nothing -- which is
 * exactly the bug this module exists to fix.
 */
describe('toDateTimeLocalInput', () => {
  it('rechnet den UTC-Zeitpunkt in die Ortszeit um', () => {
    expect(toDateTimeLocalInput('2024-03-01T08:15:30.000Z', 'Europe/Berlin')).toBe(
      '2024-03-01T09:15:30',
    )
  })

  it('berücksichtigt die Sommerzeit', () => {
    expect(toDateTimeLocalInput('2024-07-01T08:15:30.000Z', 'Europe/Berlin')).toBe(
      '2024-07-01T10:15:30',
    )
  })

  it('lässt den Zeitpunkt in UTC unverändert', () => {
    expect(toDateTimeLocalInput('2024-03-01T08:15:30.000Z', 'UTC')).toBe('2024-03-01T08:15:30')
  })

  it('verschiebt auch das Datum, wenn die Ortszeit auf den Vortag fällt', () => {
    expect(toDateTimeLocalInput('2024-03-01T00:15:30.000Z', 'America/New_York')).toBe(
      '2024-02-29T19:15:30',
    )
  })

  it('kommt mit halben Stunden Zeitverschiebung zurecht', () => {
    expect(toDateTimeLocalInput('2024-06-01T05:00:00.000Z', 'Asia/Kolkata')).toBe(
      '2024-06-01T10:30:00',
    )
  })

  /** The old `text.slice(0, 16)` cut them off, so every edited row lost its seconds. */
  it('behält die Sekunden', () => {
    expect(toDateTimeLocalInput('2024-03-01T08:15:45.000Z', 'UTC')).toBe('2024-03-01T08:15:45')
  })

  it('zeigt einen unlesbaren Wert unverändert an, statt "Invalid Date"', () => {
    expect(toDateTimeLocalInput('kein Zeitpunkt', 'Europe/Berlin')).toBe('kein Zeitpunkt')
  })
})

describe('fromDateTimeLocalInput', () => {
  it('hängt die Zeitverschiebung an -- ohne sie weist der Server die Änderung ab', () => {
    expect(fromDateTimeLocalInput('2024-03-01T09:15:30', 'Europe/Berlin')).toBe(
      '2024-03-01T09:15:30+01:00',
    )
  })

  it('nennt im Sommer die Sommerzeit-Verschiebung', () => {
    expect(fromDateTimeLocalInput('2024-07-01T10:15:30', 'Europe/Berlin')).toBe(
      '2024-07-01T10:15:30+02:00',
    )
  })

  it('ergänzt fehlende Sekunden mit null', () => {
    expect(fromDateTimeLocalInput('2024-03-01T09:15', 'Europe/Berlin')).toBe(
      '2024-03-01T09:15:00+01:00',
    )
  })

  it('schreibt eine negative Verschiebung mit Minuszeichen', () => {
    expect(fromDateTimeLocalInput('2024-02-29T19:15:30', 'America/New_York')).toBe(
      '2024-02-29T19:15:30-05:00',
    )
  })

  it('schreibt auch für UTC eine Verschiebung, nicht nichts', () => {
    expect(fromDateTimeLocalInput('2024-03-01T08:15:30', 'UTC')).toBe('2024-03-01T08:15:30+00:00')
  })

  it('kommt mit halben Stunden Zeitverschiebung zurecht', () => {
    expect(fromDateTimeLocalInput('2024-06-01T10:30:00', 'Asia/Kolkata')).toBe(
      '2024-06-01T10:30:00+05:30',
    )
  })

  it('gibt einen Wert, der kein Zeitpunkt ist, unverändert zurück', () => {
    expect(fromDateTimeLocalInput('', 'Europe/Berlin')).toBe('')
    expect(fromDateTimeLocalInput('kein Zeitpunkt', 'Europe/Berlin')).toBe('kein Zeitpunkt')
  })
})

/**
 * The two days a year on which the zone's own offset moves. Neither the ambiguous hour
 * (October, 02:30 exists twice) nor the missing one (March, 02:30 does not exist) may
 * end in a value the server rejects or in a jump of a whole hour.
 */
describe('Zeitumstellung', () => {
  it('zeigt beide Durchläufe der doppelten Stunde als dieselbe Ortszeit', () => {
    expect(toDateTimeLocalInput('2024-10-27T00:30:00.000Z', 'Europe/Berlin')).toBe(
      '2024-10-27T02:30:00',
    )
    expect(toDateTimeLocalInput('2024-10-27T01:30:00.000Z', 'Europe/Berlin')).toBe(
      '2024-10-27T02:30:00',
    )
  })

  it('entscheidet sich bei der doppelten Stunde für den zweiten Durchlauf', () => {
    expect(fromDateTimeLocalInput('2024-10-27T02:30:00', 'Europe/Berlin')).toBe(
      '2024-10-27T02:30:00+01:00',
    )
  })

  it('liefert für die übersprungene Stunde einen gültigen Zeitpunkt', () => {
    expect(fromDateTimeLocalInput('2024-03-31T02:30:00', 'Europe/Berlin')).toBe(
      '2024-03-31T02:30:00+01:00',
    )
  })

  /**
   * The one case that cannot come back unchanged, and the reason the round trip below
   * leaves it out: `datetime-local` states no zone, so the first run of the repeated hour
   * is not distinguishable from the second once it is in the field. Whoever opens such a
   * value and saves it moves it by an hour. Nothing in the field can say which of the two
   * was meant -- the alternative would be to guess silently, which is worse.
   */
  it('verschiebt den ersten Durchlauf der doppelten Stunde beim Speichern um eine Stunde', () => {
    const shown = toDateTimeLocalInput('2024-10-27T00:30:00.000Z', 'Europe/Berlin')
    const sent = fromDateTimeLocalInput(shown, 'Europe/Berlin')
    expect(new Date(sent).toISOString()).toBe('2024-10-27T01:30:00.000Z')
  })
})

describe('Hin- und Rückweg', () => {
  // The repeated hour of an autumn changeover is deliberately absent: see the test above
  // -- it is the one instant that cannot come back as itself.
  const instants = [
    '2024-01-15T23:45:00.000Z',
    '2024-03-01T08:15:30.000Z',
    '2024-03-31T00:30:00.000Z',
    '2024-07-01T08:15:30.000Z',
    '2024-10-27T02:30:00.000Z',
    '2024-12-31T23:00:00.000Z',
  ]
  const zones = ['Europe/Berlin', 'UTC', 'America/New_York', 'Asia/Kolkata']

  it('führt jeden Zeitpunkt unverändert zum Server zurück', () => {
    for (const zone of zones) {
      for (const instant of instants) {
        const shown = toDateTimeLocalInput(instant, zone)
        const sent = fromDateTimeLocalInput(shown, zone)
        expect(new Date(sent).getTime(), `${instant} in ${zone}`).toBe(Date.parse(instant))
      }
    }
  })

  it('schickt eine Zeichenkette, die der Server annimmt', () => {
    for (const zone of zones) {
      for (const instant of instants) {
        const sent = fromDateTimeLocalInput(toDateTimeLocalInput(instant, zone), zone)
        // ISO_OFFSET_DATE_TIME: the server parses exactly this shape and nothing less.
        expect(sent, `${instant} in ${zone}`).toMatch(
          /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/,
        )
      }
    }
  })
})
