import { describe, expect, test } from 'vitest'
import { describeZoomWindow, zoomWindowState } from './zoomWindow'

describe('zoomWindowState', () => {
  test('erkennt den Zoom innerhalb des Fensters', () => {
    expect(zoomWindowState(11, 22, 13)).toBe('inside')
  })

  test('erkennt einen zu weit herausgezoomten Stand', () => {
    // Der gemeldete Fall: ALKIS-Festlegungen beginnen bei 16, das Projekt oeffnet bei 9,8.
    expect(zoomWindowState(16, 22, 9.8)).toBe('below')
  })

  test('erkennt einen zu weit hineingezoomten Stand', () => {
    expect(zoomWindowState(0, 12, 15.2)).toBe('above')
  })

  test('zaehlt beide Grenzen als innerhalb', () => {
    expect(zoomWindowState(11, 22, 11)).toBe('inside')
    expect(zoomWindowState(11, 22, 22)).toBe('inside')
  })

  test('sagt nichts, solange die Karte keinen Zoom gemeldet hat', () => {
    expect(zoomWindowState(16, 22, null)).toBe('unknown')
  })
})

describe('describeZoomWindow', () => {
  test('nennt die Grenze und den aktuellen Stand', () => {
    expect(describeZoomWindow(16, 22, 9.8)).toBe('Sichtbar ab Zoom 16 — Sie sind bei 10.')
    expect(describeZoomWindow(0, 12, 15.2)).toBe('Sichtbar bis Zoom 12 — Sie sind bei 15.')
  })

  test('schweigt, wenn es nichts zu erklaeren gibt', () => {
    expect(describeZoomWindow(11, 22, 13)).toBeNull()
    expect(describeZoomWindow(16, 22, null)).toBeNull()
  })
})
