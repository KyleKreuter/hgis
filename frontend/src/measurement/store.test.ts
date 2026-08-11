import { beforeEach, describe, expect, it } from 'vitest'
import { IDLE } from './session'
import { endMeasurement, useMeasurement } from './store'

beforeEach(() => {
  useMeasurement.setState(IDLE)
})

describe('Messsitzung', () => {
  it('führt eine Streckenmessung von der Moduswahl bis zum Ergebnis', () => {
    const store = useMeasurement.getState()
    store.selectMode('distance')
    store.addVertex([13.4, 52.5])
    store.addVertex([13.41, 52.5])
    store.moveCursor([13.42, 52.5])
    store.finish()

    const state = useMeasurement.getState()
    expect(state.mode).toBe('distance')
    expect(state.points).toHaveLength(2)
    expect(state.cursor).toBeNull()
    expect(state.finished).toBe(true)
  })

  it('setzt zurück, ohne den Modus zu verlassen', () => {
    const store = useMeasurement.getState()
    store.selectMode('area')
    store.addVertex([13.4, 52.5])
    store.clear()

    expect(useMeasurement.getState()).toMatchObject({
      mode: 'area',
      points: [],
      cursor: null,
      finished: false,
    })
  })

  it('lässt beim Beenden nichts stehen -- auch keine halbe Skizze', () => {
    const store = useMeasurement.getState()
    store.selectMode('area')
    store.addVertex([13.4, 52.5])
    store.addVertex([13.41, 52.5])
    store.exit()

    expect(useMeasurement.getState()).toMatchObject(IDLE)
  })

  /**
   * `useEditSession.start` ruft das auf, bevor das Zeichenwerkzeug überhaupt
   * eingehängt wird. Über einen Effekt -- das `disabled` der Werkzeugleiste -- käme
   * das Ende einen Commit zu spät, und der Abbau der Messung machte dann Einstellungen
   * rückgängig, die das Zeichenwerkzeug schon gesetzt hat.
   */
  it('endet auf Zuruf von außen, ohne React abzuwarten', () => {
    const store = useMeasurement.getState()
    store.selectMode('area')
    store.addVertex([13.4, 52.5])

    endMeasurement()

    expect(useMeasurement.getState()).toMatchObject(IDLE)
  })

  it('hält die Punktliste stabil, während nur der Zeiger wandert', () => {
    const store = useMeasurement.getState()
    store.selectMode('distance')
    store.addVertex([13.4, 52.5])
    const points = useMeasurement.getState().points

    store.moveCursor([13.5, 52.6])
    store.moveCursor([13.6, 52.7])

    // Identität, nicht nur Gleichheit: daran hängt, dass die Karte bei jeder
    // Mausbewegung nicht die halbe Oberfläche neu rendert.
    expect(useMeasurement.getState().points).toBe(points)
  })
})
