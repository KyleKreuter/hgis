import { describe, expect, it } from 'vitest'
import {
  computeImageSize,
  describeImageSize,
  findPageChoice,
  CSS_DPI,
  PAGE_CHOICES,
  RESOLUTIONS,
} from './pageFormat'

const SCREEN = { width: 900, height: 600 }

describe('computeImageSize', () => {
  it('rechnet A4 quer bei 300 dpi in 3508 x 2480 Pixel um', () => {
    const size = computeImageSize(findPageChoice('a4-landscape'), 300, SCREEN)

    expect(size.widthPx).toBe(3508)
    expect(size.heightPx).toBe(2480)
  })

  it('dreht Breite und Höhe bei Hochformat', () => {
    const size = computeImageSize(findPageChoice('a4-portrait'), 300, SCREEN)

    expect(size.widthPx).toBe(2480)
    expect(size.heightPx).toBe(3508)
  })

  it('rechnet A3 quer bei 300 dpi in 4961 x 3508 Pixel um', () => {
    const size = computeImageSize(findPageChoice('a3-landscape'), 300, SCREEN)

    expect(size.widthPx).toBe(4961)
    expect(size.heightPx).toBe(3508)
  })

  it('verdoppelt die Pixelzahl, wenn die Auflösung sich verdoppelt', () => {
    const at150 = computeImageSize(findPageChoice('a4-landscape'), 150, SCREEN)
    const at300 = computeImageSize(findPageChoice('a4-landscape'), 300, SCREEN)

    expect(at300.widthPx).toBe(at150.widthPx * 2)
    expect(at300.heightPx).toBe(at150.heightPx * 2)
  })

  /**
   * The one rule the whole feature stands on: the CSS box stays the same size while the
   * pixel count grows. That is what keeps a label the same number of millimetres on the
   * page at 96 and at 300 dpi -- the pixel ratio absorbs the whole difference.
   */
  it('lässt die CSS-Größe gleich und trägt die Auflösung im Pixelverhältnis', () => {
    const at96 = computeImageSize(findPageChoice('a4-landscape'), 96, SCREEN)
    const at300 = computeImageSize(findPageChoice('a4-landscape'), 300, SCREEN)

    expect(at96.pixelRatio).toBe(1)
    expect(at300.pixelRatio).toBeCloseTo(3.125, 6)
    // Within half a pixel: both are derived from their own rounded pixel count, so they
    // differ by the rounding and by nothing else.
    expect(at300.cssWidth).toBeCloseTo(at96.cssWidth, 0)
    expect(at300.cssHeight).toBeCloseTo(at96.cssHeight, 0)
  })

  it('legt die CSS-Größe so, dass sie mal Pixelverhältnis genau die Bildgröße ergibt', () => {
    for (const dpi of RESOLUTIONS.map((entry) => entry.dpi)) {
      for (const choice of PAGE_CHOICES) {
        const size = computeImageSize(choice, dpi, SCREEN)
        expect(size.cssWidth * size.pixelRatio).toBeCloseTo(size.widthPx, 6)
        expect(size.cssHeight * size.pixelRatio).toBeCloseTo(size.heightPx, 6)
      }
    }
  })

  it('nimmt für „Wie am Bildschirm" die Größe des Kartenfensters', () => {
    const size = computeImageSize(findPageChoice('screen'), 96, SCREEN)

    expect(size.widthPx).toBe(900)
    expect(size.heightPx).toBe(600)
    expect(size.pixelRatio).toBe(1)
  })

  it('skaliert „Wie am Bildschirm" mit der Auflösung', () => {
    const size = computeImageSize(findPageChoice('screen'), 300, SCREEN)

    expect(size.widthPx).toBe(Math.round(900 * (300 / CSS_DPI)))
    expect(size.heightPx).toBe(Math.round(600 * (300 / CSS_DPI)))
  })

  it('liefert auch für ein noch nicht aufgebautes Kartenfenster eine gültige Größe', () => {
    const size = computeImageSize(findPageChoice('screen'), 96, { width: 0, height: 0 })

    expect(size.widthPx).toBeGreaterThan(0)
    expect(size.heightPx).toBeGreaterThan(0)
  })
})

describe('findPageChoice', () => {
  it('fällt bei einem unbekannten Wert auf den ersten Eintrag zurück', () => {
    expect(findPageChoice('gibt-es-nicht').id).toBe(PAGE_CHOICES[0].id)
  })
})

describe('describeImageSize', () => {
  it('nennt beide Kantenlängen', () => {
    expect(describeImageSize({ widthPx: 3508, heightPx: 2480 })).toBe('3508 × 2480 Pixel')
  })
})
