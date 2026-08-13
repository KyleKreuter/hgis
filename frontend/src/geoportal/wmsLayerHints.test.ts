import { describe, expect, it, test } from 'vitest'
import { formatWmsScaleLimits, preferredImageFormat, zoomWindowHint } from './wmsLayerHints'

describe('formatWmsScaleLimits', () => {
  it('nennt nichts, wenn der Dienst keine Grenze angibt', () => {
    expect(formatWmsScaleLimits(null, null)).toBeNull()
  })

  it('zeigt beide Grenzen mit einem Gedankenstrich', () => {
    expect(formatWmsScaleLimits(1000, 3000)).toBe('1:1.000 – 1:3.000')
  })

  it('zeigt nur die untere Grenze mit "ab"', () => {
    expect(formatWmsScaleLimits(1000, null)).toBe('ab 1:1.000')
  })

  it('zeigt nur die obere Grenze mit "bis"', () => {
    expect(formatWmsScaleLimits(null, 3000)).toBe('bis 1:3.000')
  })

  it('rundet und gruppiert nach deutscher Konvention', () => {
    expect(formatWmsScaleLimits(2500.4, null)).toBe('ab 1:2.500')
  })
})

describe('preferredImageFormat', () => {
  test('nimmt PNG, auch wenn der Dienst BMP zuerst nennt', () => {
    // Genau die Liste von HH_WMS_Fachdaten_ALKIS. Mit dem ersten Eintrag als Wahl kamen
    // Kacheln mit 200 zurueck, die MapLibre nicht zeichnen kann -- weisse Karte, kein Fehler.
    const alkis = ['image/bmp', 'image/jpeg', 'image/tiff', 'image/png', 'image/png8', 'image/gif']

    expect(preferredImageFormat(alkis)).toBe('image/png')
  })

  test('zieht PNG dem JPEG vor', () => {
    // Eine Auflage braucht Transparenz, und die kann JPEG nicht.
    expect(preferredImageFormat(['image/jpeg', 'image/png'])).toBe('image/png')
  })

  test('nimmt eine PNG-Schreibweise, wenn es kein schlichtes PNG gibt', () => {
    expect(preferredImageFormat(['image/jpeg', 'image/png32'])).toBe('image/png32')
  })

  test('faellt auf PNG zurueck, wenn die Liste leer ist', () => {
    expect(preferredImageFormat([])).toBe('image/png')
  })
})

describe('zoomWindowHint', () => {
  test('meldet, wenn der Layer erst weiter unten sichtbar wird', () => {
    // Der gemeldete Fall: ALKIS-Festlegungen beginnen bei Zoom 16, das Projekt oeffnet bei 9,8.
    expect(zoomWindowHint(16, 22, 9.8)).toBe('Sichtbar ab Zoom 16 — Sie sind bei 10.')
  })

  test('meldet auch die obere Grenze', () => {
    expect(zoomWindowHint(0, 12, 15.2)).toBe('Sichtbar bis Zoom 12 — Sie sind bei 15.')
  })

  test('schweigt, wenn der Layer im Fenster liegt', () => {
    expect(zoomWindowHint(11, 22, 13)).toBeNull()
  })

  test('schweigt, solange die Karte keinen Zoom gemeldet hat', () => {
    // Eine Vermutung darueber, was der Nutzer sieht, waere schlechter als kein Satz.
    expect(zoomWindowHint(16, 22, null)).toBeNull()
  })
})
