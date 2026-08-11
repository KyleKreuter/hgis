import { describe, expect, it } from 'vitest'
import {
  isMapKeyboardContext,
  measurementKeyAction,
  measurementKeyEventAction,
  type KeyTargetLike,
} from './keyboard'

/**
 * Ein Ziel, wie es ein Tastendruck mitbringt. `closest` antwortet auf genau die
 * Selektoren, die der Aufrufer als Vorfahren mitgibt -- mehr braucht die
 * Kontextprüfung nicht, und mehr müsste sonst ein DOM bereitstellen.
 */
function target(tagName: string, ancestors: string[] = [], extra: Partial<KeyTargetLike> = {}): KeyTargetLike {
  return {
    tagName,
    closest: (selector: string) => (ancestors.includes(selector) ? {} : null),
    ...extra,
  }
}

const IN_MAP = ['.maplibregl-map']

describe('isMapKeyboardContext', () => {
  it('nimmt an, was nirgends hängt: kein Ziel, document, body', () => {
    expect(isMapKeyboardContext(null)).toBe(true)
    expect(isMapKeyboardContext(undefined)).toBe(true)
    expect(isMapKeyboardContext({})).toBe(true)
    expect(isMapKeyboardContext(target('BODY'))).toBe(true)
    expect(isMapKeyboardContext(target('HTML'))).toBe(true)
  })

  it('nimmt an, was in der Karte liegt', () => {
    expect(isMapKeyboardContext(target('CANVAS', IN_MAP))).toBe(true)
    expect(isMapKeyboardContext(target('DIV', IN_MAP))).toBe(true)
  })

  /**
   * Der eigentliche Befund: der Handler hängt am `window` und hat vorher jedes Enter,
   * Escape und jede Rücktaste der Anwendung mitgenommen -- Enter und Rücktaste sogar
   * mit `preventDefault`, sodass der fokussierte Knopf gar nicht mehr auslöste.
   */
  it('lehnt Bedienelemente ab, auch wenn sie über der Karte liegen', () => {
    expect(isMapKeyboardContext(target('BUTTON'))).toBe(false)
    expect(isMapKeyboardContext(target('BUTTON', IN_MAP))).toBe(false)
    expect(isMapKeyboardContext(target('A'))).toBe(false)
    expect(isMapKeyboardContext(target('INPUT'))).toBe(false)
    expect(isMapKeyboardContext(target('TEXTAREA'))).toBe(false)
    expect(isMapKeyboardContext(target('SELECT'))).toBe(false)
  })

  /**
   * Erlaubnisliste statt Sperrliste: Dialoge, Menüs und Popovers landen per Portal
   * ohnehin außerhalb der Karte, und was dort liegt, gehört jemand anderem -- ohne
   * dass die Liste dieser Fälle hier gepflegt werden müsste.
   */
  it('lehnt alles außerhalb der Karte ab, Dialoge und Menüs eingeschlossen', () => {
    expect(isMapKeyboardContext(target('DIV', ['[role="dialog"]']))).toBe(false)
    expect(isMapKeyboardContext(target('DIV', ['[role="menu"]']))).toBe(false)
    expect(isMapKeyboardContext(target('DIV'))).toBe(false)
  })

  it('lehnt einen editierbaren Bereich ab, auch innerhalb der Karte', () => {
    expect(isMapKeyboardContext(target('DIV', IN_MAP, { isContentEditable: true }))).toBe(false)
  })
})

describe('measurementKeyAction', () => {
  it('räumt mit Escape erst die Skizze weg und verlässt erst dann den Modus', () => {
    expect(measurementKeyAction('Escape', true)).toBe('clear')
    expect(measurementKeyAction('Escape', false)).toBe('exit')
  })

  it('schließt mit Enter ab und nimmt mit der Rücktaste zurück', () => {
    expect(measurementKeyAction('Enter', true)).toBe('finish')
    expect(measurementKeyAction('Backspace', true)).toBe('undo')
  })

  it('lässt jede andere Taste unangetastet', () => {
    for (const key of ['a', 'Tab', ' ', 'ArrowLeft', 'Delete']) {
      expect(measurementKeyAction(key, true)).toBeNull()
    }
  })
})

describe('measurementKeyEventAction', () => {
  it('greift nur im Kartenkontext zu', () => {
    expect(
      measurementKeyEventAction({ key: 'Enter', target: target('CANVAS', IN_MAP) }, true),
    ).toBe('finish')
    expect(measurementKeyEventAction({ key: 'Enter', target: target('BUTTON') }, true)).toBeNull()
    expect(
      measurementKeyEventAction({ key: 'Escape', target: target('DIV', ['[role="dialog"]']) }, true),
    ).toBeNull()
    expect(
      measurementKeyEventAction({ key: 'Backspace', target: target('INPUT') }, true),
    ).toBeNull()
  })

  it('lässt liegen, was jemand anderes schon behandelt hat', () => {
    expect(
      measurementKeyEventAction(
        { key: 'Escape', target: target('CANVAS', IN_MAP), defaultPrevented: true },
        true,
      ),
    ).toBeNull()
  })
})
