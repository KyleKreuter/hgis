import { beforeEach, describe, expect, it } from 'vitest'
import { useStructure } from './structureStore'

const LINE: GeoJSON.LineString = {
  type: 'LineString',
  coordinates: [
    [9.98, 53.55],
    [9.99, 53.56],
  ],
}

describe('useStructure', () => {
  beforeEach(() => {
    useStructure.getState().cancel()
  })

  it('merkt sich beim Scharfstellen Objekt und rowVersion', () => {
    useStructure.getState().startSplit(42, '8241')

    expect(useStructure.getState().phase).toEqual({
      type: 'drawing',
      fid: 42,
      rowVersion: '8241',
      points: [],
    })
  })

  it('trägt die rowVersion vom Scharfstellen bis zur Rückfrage', () => {
    // The version says which state of the row the cut was planned against. Re-reading it
    // after the line was drawn would paper over exactly the conflict a 409 reports.
    useStructure.getState().startSplit(42, '8241')
    useStructure.getState().addPoint([9.98, 53.55])
    useStructure.getState().addPoint([9.99, 53.56])
    useStructure.getState().finishLine(LINE)

    expect(useStructure.getState().phase).toEqual({
      type: 'confirmSplit',
      fid: 42,
      rowVersion: '8241',
      line: LINE,
    })
  })

  it('sammelt Klicks und nimmt sie einzeln zurück', () => {
    useStructure.getState().startSplit(42, '8241')
    useStructure.getState().addPoint([9.98, 53.55])
    useStructure.getState().addPoint([9.99, 53.56])
    useStructure.getState().undoPoint()

    const phase = useStructure.getState().phase
    expect(phase.type === 'drawing' && phase.points).toEqual([[9.98, 53.55]])
  })

  it('verwirft mit clearPoints die Zeichnung, bleibt aber scharf', () => {
    useStructure.getState().startSplit(42, '8241')
    useStructure.getState().addPoint([9.98, 53.55])
    useStructure.getState().clearPoints()

    const phase = useStructure.getState().phase
    expect(phase.type).toBe('drawing')
    expect(phase.type === 'drawing' && phase.points).toEqual([])
  })

  it('nimmt Klicks nur an, solange gezeichnet wird', () => {
    // Every transition is guarded rather than trusting the caller: the map handlers
    // outlive a phase change by at least one event.
    useStructure.getState().openMerge()
    useStructure.getState().addPoint([9.98, 53.55])
    useStructure.getState().undoPoint()
    useStructure.getState().finishLine(LINE)

    expect(useStructure.getState().phase).toEqual({ type: 'merge' })
  })

  it('führt vom Neuzeichnen zurück in eine leere Zeichnung', () => {
    useStructure.getState().startSplit(42, '8241')
    useStructure.getState().finishLine(LINE)
    useStructure.getState().redrawLine()

    expect(useStructure.getState().phase).toEqual({
      type: 'drawing',
      fid: 42,
      rowVersion: '8241',
      points: [],
    })
  })

  it('räumt mit cancel aus jeder Phase auf', () => {
    useStructure.getState().startSplit(42, '8241')
    useStructure.getState().cancel()

    expect(useStructure.getState().phase).toEqual({ type: 'idle' })
  })
})
