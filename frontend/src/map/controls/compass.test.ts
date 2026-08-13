import { describe, expect, it } from 'vitest'
import { compassLabel, isViewOriented, normalizeBearing } from './compass'

describe('normalizeBearing', () => {
  it('lässt einen Wert im Zielbereich unverändert', () => {
    expect(normalizeBearing(35)).toBe(35)
    expect(normalizeBearing(-120)).toBe(-120)
  })

  it('führt eine volle Umdrehung auf null zurück', () => {
    expect(normalizeBearing(360)).toBe(0)
    expect(normalizeBearing(720)).toBe(0)
  })

  it('rechnet über 180 in den negativen Bereich', () => {
    expect(normalizeBearing(270)).toBe(-90)
    expect(normalizeBearing(200)).toBe(-160)
  })

  it('nimmt auch negative Vielfache an', () => {
    expect(normalizeBearing(-370)).toBe(-10)
  })
})

describe('isViewOriented', () => {
  it('meldet eine unverdrehte, flache Karte als nicht ausgerichtet', () => {
    expect(isViewOriented(0, 0)).toBe(false)
  })

  it('wertet einen Rundungsrest nicht als Drehung', () => {
    // Nach dem Zurückdrehen von Hand bleibt selten eine glatte Null stehen.
    expect(isViewOriented(0.0003, 0)).toBe(false)
    expect(isViewOriented(0, 0.2)).toBe(false)
  })

  it('erkennt eine Drehung', () => {
    expect(isViewOriented(35, 0)).toBe(true)
  })

  it('erkennt eine Neigung ohne Drehung', () => {
    expect(isViewOriented(0, 30)).toBe(true)
  })

  it('erkennt eine Drehung dicht unter der vollen Umdrehung', () => {
    // 359 Grad sind ein Grad Drehung, nicht 359 -- ohne Normalisierung fiele das durch.
    expect(isViewOriented(359, 0)).toBe(true)
  })
})

describe('compassLabel', () => {
  it('nennt nur Norden, solange die Karte flach ist', () => {
    expect(compassLabel(35, 0)).toBe('Norden oben')
    expect(compassLabel(0, 0)).toBe('Norden oben')
  })

  it('nennt nur die Neigung, solange Norden oben ist', () => {
    expect(compassLabel(0, 30)).toBe('Neigung zurücksetzen')
  })

  it('nennt beides, wenn beides abweicht', () => {
    expect(compassLabel(35, 30)).toBe('Norden oben, Neigung zurücksetzen')
  })
})
