import { describe, expect, it } from 'vitest'
import { bboxFromCorners, isMeaningfulDrag, rectanglePolygon } from './rectangleSelectGeometry'

describe('bboxFromCorners', () => {
  it('ordnet die Ecken unabhängig von der Zugrichtung', () => {
    expect(bboxFromCorners([10, 50], [8, 52])).toEqual([8, 50, 10, 52])
  })

  it('liefert dasselbe Ergebnis für die entgegengesetzte Zugrichtung', () => {
    expect(bboxFromCorners([8, 52], [10, 50])).toEqual([8, 50, 10, 52])
  })

  it('kollabiert einen Zug ohne Ausdehnung zu einer Punkt-Bbox', () => {
    expect(bboxFromCorners([5, 5], [5, 5])).toEqual([5, 5, 5, 5])
  })
})

describe('isMeaningfulDrag', () => {
  it('ignoriert einen Zug unterhalb der Schwelle', () => {
    expect(isMeaningfulDrag([100, 100], [101, 100])).toBe(false)
  })

  it('akzeptiert einen Zug, der die Schwelle horizontal überschreitet', () => {
    expect(isMeaningfulDrag([100, 100], [104, 100])).toBe(true)
  })

  it('akzeptiert einen Zug, der die Schwelle vertikal überschreitet', () => {
    expect(isMeaningfulDrag([100, 100], [100, 104])).toBe(true)
  })

  it('respektiert eine eigene Schwelle', () => {
    expect(isMeaningfulDrag([100, 100], [108, 100], 10)).toBe(false)
  })
})

describe('rectanglePolygon', () => {
  it('schließt den Ring und hält eine stabile Eckreihenfolge ein', () => {
    const polygon = rectanglePolygon([8, 50, 10, 52])
    expect(polygon.coordinates).toEqual([
      [
        [8, 50],
        [10, 50],
        [10, 52],
        [8, 52],
        [8, 50],
      ],
    ])
  })
})
