import { describe, expect, it } from 'vitest'
import { formatArea, formatDistance } from './format'

describe('formatDistance', () => {
  it('bleibt unterhalb eines Kilometers bei Metern', () => {
    expect(formatDistance(4.732)).toBe('4,73 m')
    expect(formatDistance(84.51)).toBe('84,5 m')
    expect(formatDistance(845.34)).toBe('845,3 m')
    expect(formatDistance(999.9)).toBe('999,9 m')
  })

  it('wechselt ab einem Kilometer auf km', () => {
    expect(formatDistance(1000)).toBe('1,00 km')
    expect(formatDistance(1234.5)).toBe('1,23 km')
    expect(formatDistance(45_678)).toBe('45,7 km')
    expect(formatDistance(1_234_567)).toBe('1.235 km')
  })

  /**
   * Die Einheit muss aus der gerundeten Zahl folgen, nicht aus der rohen: 999,96 m
   * liegt unter dem Kilometer, wird aber mit einer Nachkommastelle angezeigt -- und
   * ergab damit "1.000,0 m", also genau die Einheit, die der Wechsel verhindern soll.
   */
  it('wechselt die Einheit, sobald die Anzeige selbst sie erreicht', () => {
    expect(formatDistance(999.96)).toBe('1,00 km')
    expect(formatDistance(999.949)).toBe('999,9 m')
    expect(formatDistance(999.999)).toBe('1,00 km')
  })

  it('nennt die leere Messung null Meter', () => {
    expect(formatDistance(0)).toBe('0 m')
    expect(formatDistance(-1)).toBe('0 m')
    expect(formatDistance(Number.NaN)).toBe('0 m')
  })
})

describe('formatArea', () => {
  it('bleibt bis zu einem Hektar bei Quadratmetern', () => {
    expect(formatArea(7.5)).toBe('7,50 m²')
    expect(formatArea(842.3)).toBe('842,3 m²')
    expect(formatArea(8432.4)).toBe('8.432 m²')
  })

  it('nutzt zwischen einem Hektar und einem Quadratkilometer Hektar', () => {
    expect(formatArea(10_000)).toBe('1,00 ha')
    expect(formatArea(24_500)).toBe('2,45 ha')
    expect(formatArea(999_000)).toBe('99,9 ha')
  })

  it('wechselt ab einem Quadratkilometer auf km²', () => {
    expect(formatArea(1_000_000)).toBe('1,00 km²')
    expect(formatArea(2_500_000)).toBe('2,50 km²')
    expect(formatArea(123_456_789)).toBe('123,5 km²')
  })

  it('wechselt an beiden Grenzen erst, wenn die Anzeige sie erreicht', () => {
    expect(formatArea(9999.6)).toBe('1,00 ha')
    expect(formatArea(9999.4)).toBe('9.999 m²')
    expect(formatArea(999_960)).toBe('1,00 km²')
    expect(formatArea(999_400)).toBe('99,9 ha')
  })

  it('nennt die leere Messung null Quadratmeter', () => {
    expect(formatArea(0)).toBe('0 m²')
    expect(formatArea(-5)).toBe('0 m²')
  })
})
