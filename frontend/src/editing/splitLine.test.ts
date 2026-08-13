import { describe, expect, it } from 'vitest'
import {
  splitLineFeatures,
  splitLineKeyAction,
  splitLineKeyEventAction,
  toSplitLine,
  type SplitLinePoint,
} from './splitLine'

const A: SplitLinePoint = [9.98, 53.55]
const B: SplitLinePoint = [9.99, 53.56]
const C: SplitLinePoint = [10.0, 53.57]

describe('toSplitLine', () => {
  it('gibt unter zwei Punkten keine Linie zurück', () => {
    // A LineString with one coordinate is not valid GeoJSON. Without this the server
    // would answer a malformed body with a 400 that says nothing about what was done.
    expect(toSplitLine([])).toBeNull()
    expect(toSplitLine([A])).toBeNull()
  })

  it('baut aus zwei Punkten eine Linie in EPSG:4326', () => {
    expect(toSplitLine([A, B])).toEqual({ type: 'LineString', coordinates: [A, B] })
  })

  it('kopiert die Koordinaten, statt sie zu teilen', () => {
    // The draft keeps being edited after the line was taken from it; a shared array
    // would let a later click change a line that is already on its way to the server.
    const points: SplitLinePoint[] = [A, B]
    const line = toSplitLine(points)

    expect(line?.coordinates[0]).not.toBe(points[0])
  })
})

describe('splitLineFeatures', () => {
  it('zeichnet vor dem zweiten Punkt nur den Stützpunkt', () => {
    const { features } = splitLineFeatures({ points: [A], cursor: null })

    expect(features.map((feature) => feature.properties?.role)).toEqual(['vertex'])
  })

  it('zeichnet Linie, Gummiband und je einen Stützpunkt', () => {
    const { features } = splitLineFeatures({ points: [A, B], cursor: C })

    expect(features.map((feature) => feature.properties?.role)).toEqual([
      'line',
      'pending',
      'vertex',
      'vertex',
    ])
  })

  it('lässt das Gummiband weg, sobald der Zeiger die Karte verlassen hat', () => {
    const { features } = splitLineFeatures({ points: [A, B], cursor: null })

    expect(features.map((feature) => feature.properties?.role)).not.toContain('pending')
  })

  it('spannt das Gummiband vom letzten Punkt zum Zeiger', () => {
    const { features } = splitLineFeatures({ points: [A, B], cursor: C })
    const pending = features.find((feature) => feature.properties?.role === 'pending')

    expect(pending?.geometry).toEqual({ type: 'LineString', coordinates: [B, C] })
  })
})

describe('splitLineKeyAction', () => {
  it('verwirft mit Escape zuerst die Zeichnung, erst dann das Werkzeug', () => {
    // One press never does both -- the same rule the measuring tool follows.
    expect(splitLineKeyAction('Escape', 2)).toBe('clear')
    expect(splitLineKeyAction('Escape', 0)).toBe('cancel')
  })

  it('beendet mit Enter erst, wenn die Linie gesendet werden könnte', () => {
    expect(splitLineKeyAction('Enter', 1)).toBeNull()
    expect(splitLineKeyAction('Enter', 2)).toBe('finish')
  })

  it('nimmt mit Backspace einen Punkt zurück, aber nicht ohne Punkte', () => {
    expect(splitLineKeyAction('Backspace', 1)).toBe('undo')
    expect(splitLineKeyAction('Backspace', 0)).toBeNull()
  })

  it('lässt jede andere Taste in Ruhe', () => {
    expect(splitLineKeyAction('a', 2)).toBeNull()
    expect(splitLineKeyAction('Delete', 2)).toBeNull()
  })
})

describe('splitLineKeyEventAction', () => {
  it('lässt eine bereits behandelte Taste liegen', () => {
    expect(splitLineKeyEventAction({ key: 'Escape', defaultPrevented: true }, 2)).toBeNull()
  })

  it('beansprucht keine Taste, die einem Bedienelement gilt', () => {
    // The listener sits on `window`: without this, Escape in an open dialog and
    // Backspace in a text field would end up here.
    const button = { tagName: 'BUTTON', closest: () => null }

    expect(splitLineKeyEventAction({ key: 'Enter', target: button }, 2)).toBeNull()
  })

  it('beansprucht die Taste, wenn nichts fokussiert ist', () => {
    expect(splitLineKeyEventAction({ key: 'Enter', target: { tagName: 'BODY' } }, 2)).toBe('finish')
  })
})
