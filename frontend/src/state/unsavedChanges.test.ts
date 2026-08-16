import { describe, expect, it } from 'vitest'
import {
  describeUnsavedChanges,
  describeUnsavedWork,
  hasUnsavedChanges,
  hasUnsavedWork,
  totalUnsavedChanges,
  unsavedChangesVerb,
} from './unsavedChanges'

describe('totalUnsavedChanges', () => {
  it('adds both modes together', () => {
    expect(totalUnsavedChanges(2, 3)).toBe(5)
  })

  it('is 0 when both modes are clean', () => {
    expect(totalUnsavedChanges(0, 0)).toBe(0)
  })

  it('counts a change from either mode alone', () => {
    expect(totalUnsavedChanges(4, 0)).toBe(4)
    expect(totalUnsavedChanges(0, 7)).toBe(7)
  })
})

describe('hasUnsavedChanges', () => {
  it('is false once neither buffer has anything pending', () => {
    expect(hasUnsavedChanges(0, 0)).toBe(false)
  })

  it('is true as soon as either buffer has something pending', () => {
    expect(hasUnsavedChanges(1, 0)).toBe(true)
    expect(hasUnsavedChanges(0, 1)).toBe(true)
    expect(hasUnsavedChanges(2, 3)).toBe(true)
  })
})

describe('describeUnsavedChanges', () => {
  it('uses the singular for exactly one change', () => {
    expect(describeUnsavedChanges(1)).toBe('1 ungespeicherte Änderung')
  })

  it('uses the plural for more than one change', () => {
    expect(describeUnsavedChanges(3)).toBe('3 ungespeicherte Änderungen')
  })

  it('uses the plural for zero too -- there is no "0th change"', () => {
    expect(describeUnsavedChanges(0)).toBe('0 ungespeicherte Änderungen')
  })
})

describe('unsavedChangesVerb', () => {
  it('is "geht" for exactly one change', () => {
    expect(unsavedChangesVerb(1)).toBe('geht')
  })

  it('is "gehen" for more than one, and for zero', () => {
    expect(unsavedChangesVerb(0)).toBe('gehen')
    expect(unsavedChangesVerb(2)).toBe('gehen')
  })
})

/**
 * Eine angefangene Zeichnung ist Arbeit, ohne eine Aenderung zu sein: sie erreicht den
 * Puffer erst beim Schliessen der Form. Vorher zaehlte sie nirgends -- und ein Wechsel
 * warf sie kommentarlos weg.
 */
describe('hasUnsavedWork', () => {
  it('zaehlt gepufferte Aenderungen wie bisher', () => {
    expect(hasUnsavedWork({ mapChanges: 1, tableChanges: 0, sketching: false })).toBe(true)
    expect(hasUnsavedWork({ mapChanges: 0, tableChanges: 2, sketching: false })).toBe(true)
  })

  it('zaehlt eine angefangene Zeichnung, obwohl kein Puffer etwas haelt', () => {
    expect(hasUnsavedWork({ mapChanges: 0, tableChanges: 0, sketching: true })).toBe(true)
  })

  it('meldet nichts, wenn wirklich nichts offen ist', () => {
    expect(hasUnsavedWork({ mapChanges: 0, tableChanges: 0, sketching: false })).toBe(false)
  })

  it('unterscheidet sich genau in diesem einen Fall von hasUnsavedChanges', () => {
    // Der Fall, an dem der Fehler hing: der Zaehler sagt null, verloren geht trotzdem etwas.
    expect(hasUnsavedChanges(0, 0)).toBe(false)
    expect(hasUnsavedWork({ mapChanges: 0, tableChanges: 0, sketching: true })).toBe(true)
  })
})

describe('describeUnsavedWork', () => {
  it('bleibt beim gewohnten Satz, wenn nur Aenderungen offen sind', () => {
    expect(describeUnsavedWork({ mapChanges: 3, tableChanges: 0, sketching: false }))
      .toBe(`${describeUnsavedChanges(3)} ${unsavedChangesVerb(3)}`)
    expect(describeUnsavedWork({ mapChanges: 1, tableChanges: 0, sketching: false }))
      .toBe('1 ungespeicherte Änderung geht')
  })

  it('nennt die angefangene Zeichnung, wenn sonst nichts offen ist', () => {
    // Ohne diesen Zweig stuende dort "0 ungespeicherte Änderungen gehen verloren" --
    // eine Warnung, die sich selbst widerspricht.
    expect(describeUnsavedWork({ mapChanges: 0, tableChanges: 0, sketching: true }))
      .toBe('Eine angefangene Zeichnung geht')
  })

  it('nennt beides, wenn beides offen ist -- und dann im Plural', () => {
    expect(describeUnsavedWork({ mapChanges: 2, tableChanges: 0, sketching: true }))
      .toBe('2 ungespeicherte Änderungen und eine angefangene Zeichnung gehen')
    expect(describeUnsavedWork({ mapChanges: 1, tableChanges: 0, sketching: true }))
      .toBe('1 ungespeicherte Änderung und eine angefangene Zeichnung gehen')
  })
})
