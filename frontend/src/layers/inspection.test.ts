import { describe, expect, it } from 'vitest'
import { formatCharset, formatFeatureCount, formatLocation, formatSample } from './inspection'

describe('formatLocation', () => {
  it('verortet eine Bbox über ihren Mittelpunkt', () => {
    expect(formatLocation([9.85, 53.4, 9.95, 53.6])).toBe('53,5° N / 9,9° O')
    // Mittelpunkt 9,985 -- gerundet, nicht abgeschnitten.
    expect(formatLocation([9.98, 53.54, 9.99, 53.55])).toBe('53,5° N / 10,0° O')
  })

  it('nennt die Himmelsrichtung statt eines Vorzeichens', () => {
    expect(formatLocation([-43.3, -23.0, -43.1, -22.8])).toBe('22,9° S / 43,2° W')
  })

  it('behandelt Null-Grad als Nord und Ost', () => {
    expect(formatLocation([0, 0, 0, 0])).toBe('0,0° N / 0,0° O')
  })

  it('liefert null, wenn es nichts zu verorten gibt', () => {
    expect(formatLocation(null)).toBeNull()
    expect(formatLocation([Number.NaN, 0, 0, 0])).toBeNull()
    expect(formatLocation([0, Number.POSITIVE_INFINITY, 0, 0])).toBeNull()
  })
})

describe('formatSample', () => {
  it('unterscheidet NULL von einem leeren String', () => {
    expect(formatSample(null)).toEqual({ text: 'NULL', placeholder: true })
    expect(formatSample('')).toEqual({ text: 'leer', placeholder: true })
  })

  it('markiert reine Leerzeichen als solche', () => {
    expect(formatSample('   ')).toEqual({ text: 'nur Leerzeichen', placeholder: true })
  })

  it('gibt Werte mit Inhalt unverändert wieder', () => {
    // Genau darum geht es: die falsch dekodierte Fassung muss sichtbar bleiben.
    expect(formatSample('MÃ¼llerstraÃŸe')).toEqual({
      text: 'MÃ¼llerstraÃŸe',
      placeholder: false,
    })
    expect(formatSample(' Müllerstraße ')).toEqual({
      text: ' Müllerstraße ',
      placeholder: false,
    })
    expect(formatSample('0')).toEqual({ text: '0', placeholder: false })
  })
})

describe('formatFeatureCount', () => {
  it('gruppiert die Zahl deutsch', () => {
    expect(formatFeatureCount(1003)).toBe('1.003 Objekte')
    expect(formatFeatureCount(0)).toBe('0 Objekte')
  })

  it('sagt es, wenn das Format keine Anzahl kennt', () => {
    expect(formatFeatureCount(null)).toBe('Anzahl unbekannt')
  })
})

describe('formatCharset', () => {
  it('nennt die verwendete Kodierung', () => {
    expect(formatCharset('windows-1252')).toBe('windows-1252')
  })

  it('erklärt, wenn das Format die Kodierung selbst festlegt', () => {
    expect(formatCharset(null)).toBe('Kodierung vom Format vorgegeben')
  })
})
