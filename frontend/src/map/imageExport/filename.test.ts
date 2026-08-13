import { describe, expect, it } from 'vitest'
import { imageFilename } from './filename'

const DAY = new Date(2026, 7, 13, 23, 30)

describe('imageFilename', () => {
  it('baut den Namen aus Titel und Datum', () => {
    expect(imageFilename('Baumkataster', DAY)).toBe('Baumkataster_2026-08-13.png')
  })

  it('behält Umlaute', () => {
    expect(imageFilename('Grünflächen', DAY)).toBe('Grünflächen_2026-08-13.png')
  })

  it('ersetzt Leerzeichen und verbotene Zeichen', () => {
    expect(imageFilename('Bezirk Nord / Bäume: 2026', DAY)).toBe(
      'Bezirk-Nord-Bäume-2026_2026-08-13.png',
    )
  })

  it('fällt ohne Titel auf einen festen Namen zurück', () => {
    expect(imageFilename('   ', DAY)).toBe('Karte_2026-08-13.png')
  })

  it('kürzt einen sehr langen Titel und lässt keinen Trenner am Ende stehen', () => {
    const name = imageFilename('a '.repeat(80), DAY)

    expect(name.length).toBeLessThan(100)
    expect(name).not.toContain('-_')
  })

  /** Local date, not UTC: an export at 23:30 belongs to the day it was made on. */
  it('nimmt das lokale Datum, nicht das UTC-Datum', () => {
    expect(imageFilename('Karte', new Date(2026, 11, 31, 23, 59))).toBe('Karte_2026-12-31.png')
  })
})
