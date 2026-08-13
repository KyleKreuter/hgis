import { describe, expect, it } from 'vitest'
import { initialDraftFromChar, kindOf, toInputValue, toWireValue } from './fieldKind'

describe('kindOf', () => {
  it('erkennt Zeitstempel an ihrem Präfix', () => {
    expect(kindOf('timestamptz')).toBe('timestamp')
    expect(kindOf('timestamp')).toBe('timestamp')
    expect(kindOf('TIMESTAMPTZ')).toBe('timestamp')
  })

  it('unterscheidet date und time', () => {
    expect(kindOf('date')).toBe('date')
    expect(kindOf('time')).toBe('time')
  })

  it('erkennt boolean', () => {
    expect(kindOf('boolean')).toBe('boolean')
  })

  it('behandelt uuid und bytea als schreibgeschützt', () => {
    expect(kindOf('uuid')).toBe('readonly')
    expect(kindOf('bytea')).toBe('readonly')
  })

  it('erkennt ganzzahlige Typen', () => {
    expect(kindOf('smallint')).toBe('integer')
    expect(kindOf('integer')).toBe('integer')
    expect(kindOf('bigint')).toBe('integer')
  })

  it('erkennt Fließkomma- und Numerik-Typen, auch mit Präzisionsangabe', () => {
    expect(kindOf('double precision')).toBe('decimal')
    expect(kindOf('numeric')).toBe('decimal')
    expect(kindOf('numeric(10,2)')).toBe('decimal')
    expect(kindOf('real')).toBe('decimal')
    expect(kindOf('decimal')).toBe('decimal')
  })

  it('fällt für alles andere auf Text zurück', () => {
    expect(kindOf('text')).toBe('text')
    expect(kindOf('varchar(255)')).toBe('text')
    expect(kindOf('character varying')).toBe('text')
  })
})

describe('toInputValue', () => {
  /**
   * This used to assert the trim to sixteen characters, which was the bug: the field
   * means local time, so the UTC text put it an hour off in Berlin and dropped the
   * seconds. Written without naming a zone, so it holds wherever the tests run --
   * `timestampValue.test.ts` names the zones and pins the exact hours.
   */
  it('rechnet einen Zeitstempel in die Ortszeit des Browsers um', () => {
    const shown = toInputValue('2024-03-01T08:15:30.000Z', 'timestamp')
    expect(shown).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
    // Read as local time -- which is what datetime-local means -- it is the same instant.
    expect(new Date(shown).getTime()).toBe(Date.parse('2024-03-01T08:15:30.000Z'))
  })

  it('lässt ein reines Datum unverändert', () => {
    expect(toInputValue('2024-03-01', 'date')).toBe('2024-03-01')
  })

  it('lässt eine Uhrzeit unverändert, auch mit Sekunden', () => {
    expect(toInputValue('08:15:30', 'time')).toBe('08:15:30')
  })

  it('wandelt Zahlen in Text um', () => {
    expect(toInputValue(12.75, 'decimal')).toBe('12.75')
  })
})

describe('toWireValue', () => {
  /**
   * The reported bug: the cell editor sent the `datetime-local` value as it stood.
   * The server parses `ISO_OFFSET_DATE_TIME` and answered 400 -- and because one save is
   * one transaction, every other pending change in the batch was lost with it.
   */
  it('ergänzt bei einem Zeitstempel die Zeitverschiebung', () => {
    const sent = toWireValue('2024-03-01T09:15:30', 'timestamp')
    expect(sent).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/)
  })

  it('lässt Datum, Uhrzeit und Text unverändert -- die stehen schon im Format des Servers', () => {
    expect(toWireValue('2024-03-01', 'date')).toBe('2024-03-01')
    expect(toWireValue('08:15:30', 'time')).toBe('08:15:30')
    expect(toWireValue('Schmidt', 'text')).toBe('Schmidt')
  })
})

describe('initialDraftFromChar', () => {
  /**
   * The reported bug: a cell of an integer column opened by typing "6" kept the draft as
   * the text "6", and pressing Enter without typing more saved that text. The server
   * answered "Feld Zustand erwartet den Typ integer. Erhalten: 6" -- a message that reads
   * like a contradiction, because the printed value looks like a perfectly good integer.
   */
  it('macht aus dem getippten Zeichen in einer Zahlenspalte sofort eine Zahl', () => {
    expect(initialDraftFromChar('6', 'integer')).toBe(6)
    expect(initialDraftFromChar('6', 'decimal')).toBe(6)
  })

  it('lässt das Zeichen in einer Textspalte ein Zeichen', () => {
    expect(initialDraftFromChar('6', 'text')).toBe('6')
  })

  /** The start of a number that is still being typed -- NaN would be worse than text. */
  it('lässt ein Zeichen als Text stehen, das für sich noch keine Zahl ist', () => {
    expect(initialDraftFromChar('-', 'integer')).toBe('-')
    expect(initialDraftFromChar('.', 'decimal')).toBe('.')
  })
})
