import { describe, expect, it } from 'vitest'
import { CATEGORY_PALETTE, COLOR_RAMPS } from './defaults'
import { DEFAULT_CATEGORY_PALETTE, DEFAULT_RAMP, paletteColors, resolvePaletteId } from './palettes'

describe('paletteColors', () => {
  it('liefert die Kategorien-Palette für die kategoriale Auswahl', () => {
    expect(paletteColors(DEFAULT_CATEGORY_PALETTE, 3)).toEqual(CATEGORY_PALETTE.slice(0, 3))
  })

  it('faellt bei einem unbekannten Namen auf DEFAULT_RAMP zurueck', () => {
    expect(paletteColors('nicht-im-katalog', 3)).toEqual(paletteColors(DEFAULT_RAMP, 3))
  })
})

describe('resolvePaletteId', () => {
  it('laesst jeden Katalog-Namen unveraendert', () => {
    for (const ramp of COLOR_RAMPS) {
      expect(resolvePaletteId(ramp.id)).toBe(ramp.id)
    }
  })

  it('laesst die kategoriale Palette unveraendert', () => {
    expect(resolvePaletteId(DEFAULT_CATEGORY_PALETTE)).toBe(DEFAULT_CATEGORY_PALETTE)
  })

  /**
   * Team review, package 3 addendum: `CategorizedEditor.tsx`s `recolor` schreibt genau
   * dieses Ergebnis zurück in `renderer.palette`, statt des Namens, den es bekommen hat --
   * ein Stil, der einen inzwischen umbenannten oder entfernten Namen trägt, soll nach dem
   * Neuverteilen `DEFAULT_RAMP` behaupten, nicht weiter den alten, nicht mehr auflösbaren
   * Namen.
   */
  it('loest einen unbekannten Namen auf DEFAULT_RAMP auf, nicht auf sich selbst', () => {
    expect(resolvePaletteId('brewer-set2')).toBe(DEFAULT_RAMP)
    expect(resolvePaletteId('brewer-set2')).not.toBe('brewer-set2')
  })

  /**
   * Der eigentliche Vertrag zwischen den beiden Funktionen: was `resolvePaletteId` als
   * aufgelösten Namen zurückgibt, muss dieselben Farben liefern, die `paletteColors` für
   * den ursprünglichen, unaufgelösten Namen ohnehin schon malt -- sonst könnte der
   * geschriebene Zustand wieder etwas anderes behaupten, als tatsächlich gemalt wurde.
   */
  it('liefert für jeden Namen einen aufgeloesten Namen, der dieselben Farben ergibt', () => {
    for (const name of ['blues', 'inferno', DEFAULT_CATEGORY_PALETTE, 'brewer-set2', '']) {
      const resolved = resolvePaletteId(name)
      expect(paletteColors(resolved, 6)).toEqual(paletteColors(name, 6))
    }
  })
})
