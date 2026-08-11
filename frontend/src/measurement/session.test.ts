import { describe, expect, it } from 'vitest'
import type { LngLat } from './geodesy'
import {
  IDLE,
  addVertex,
  canFinishSketch,
  clearSketch,
  finishSketch,
  measurementResult,
  moveCursor,
  selectMode,
  sketchFeatures,
  sketchPath,
  undoVertex,
  type MeasurementState,
} from './session'

const A: LngLat = [13.4, 52.5]
const B: LngLat = [13.41, 52.5]
const C: LngLat = [13.41, 52.51]

/** Applies a sequence of transitions, as a click-by-click session would. */
function run(
  start: MeasurementState,
  ...steps: ((state: MeasurementState) => MeasurementState)[]
): MeasurementState {
  return steps.reduce((state, step) => step(state), start)
}

describe('Moduswahl', () => {
  it('schaltet den Modus ein und beim zweiten Druck wieder aus', () => {
    const distance = selectMode(IDLE, 'distance')
    expect(distance.mode).toBe('distance')
    expect(selectMode(distance, 'distance')).toEqual(IDLE)
  })

  it('verwirft die Geometrie beim Wechsel zwischen den Werkzeugen', () => {
    const drawn = run(selectMode(IDLE, 'distance'), (s) => addVertex(s, A), (s) => addVertex(s, B))
    const switched = selectMode(drawn, 'area')
    expect(switched.mode).toBe('area')
    expect(switched.points).toEqual([])
  })

  it('nimmt ohne Modus keine Punkte an', () => {
    expect(addVertex(IDLE, A)).toEqual(IDLE)
    expect(moveCursor(IDLE, A)).toEqual(IDLE)
    expect(finishSketch(IDLE)).toEqual(IDLE)
  })
})

/**
 * Der Abschluss-Knopf in der Werkzeugleiste hängt daran. Ohne ihn gäbe es zum
 * Beenden nur den Doppelklick -- und den kann ein Touchgerät nicht erzeugen.
 */
describe('canFinishSketch', () => {
  it('erlaubt den Abschluss erst ab genügend Punkten', () => {
    const one = run(selectMode(IDLE, 'distance'), (s) => addVertex(s, A))
    expect(canFinishSketch(one)).toBe(false)
    expect(canFinishSketch(addVertex(one, B))).toBe(true)
  })

  it('verlangt für eine Fläche drei Punkte', () => {
    const two = run(selectMode(IDLE, 'area'), (s) => addVertex(s, A), (s) => addVertex(s, B))
    expect(canFinishSketch(two)).toBe(false)
    expect(canFinishSketch(addVertex(two, C))).toBe(true)
  })

  it('ist ohne Modus und bei bereits abgeschlossener Messung falsch', () => {
    expect(canFinishSketch(IDLE)).toBe(false)
    const done = run(
      selectMode(IDLE, 'distance'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      finishSketch,
    )
    expect(canFinishSketch(done)).toBe(false)
  })
})

describe('Punkte setzen', () => {
  it('hängt Punkte in Klickreihenfolge an', () => {
    const state = run(selectMode(IDLE, 'distance'), (s) => addVertex(s, A), (s) => addVertex(s, B))
    expect(state.points).toEqual([A, B])
  })

  it('ignoriert den zweiten Klick eines Doppelklicks', () => {
    const state = run(
      selectMode(IDLE, 'distance'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      (s) => addVertex(s, [B[0], B[1]]),
    )
    expect(state.points).toEqual([A, B])
  })

  it('nimmt den letzten Punkt zurück, aber nicht mehr als vorhanden', () => {
    const state = run(selectMode(IDLE, 'distance'), (s) => addVertex(s, A), (s) => addVertex(s, B))
    expect(undoVertex(state).points).toEqual([A])
    expect(undoVertex(undoVertex(undoVertex(state))).points).toEqual([])
  })

  it('beginnt nach einer abgeschlossenen Messung eine neue', () => {
    const finished = run(
      selectMode(IDLE, 'distance'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      finishSketch,
    )
    expect(finished.finished).toBe(true)

    const next = addVertex(finished, C)
    expect(next.finished).toBe(false)
    expect(next.points).toEqual([C])
  })
})

describe('Abschließen und Zurücksetzen', () => {
  it('friert eine Strecke ab zwei Punkten ein', () => {
    const state = run(
      selectMode(IDLE, 'distance'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      (s) => moveCursor(s, C),
      finishSketch,
    )
    expect(state.finished).toBe(true)
    expect(state.cursor).toBeNull()
    // Der Cursor zählt nach dem Abschluss nicht mehr mit.
    expect(sketchPath(state)).toEqual([A, B])
  })

  it('verwirft eine Strecke mit nur einem Punkt statt sie einzufrieren', () => {
    const state = run(selectMode(IDLE, 'distance'), (s) => addVertex(s, A), finishSketch)
    expect(state.finished).toBe(false)
    expect(state.points).toEqual([])
    expect(state.mode).toBe('distance')
  })

  it('verlangt für eine Fläche drei Punkte', () => {
    const twoPoints = run(
      selectMode(IDLE, 'area'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      finishSketch,
    )
    expect(twoPoints.points).toEqual([])

    const threePoints = run(
      selectMode(IDLE, 'area'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      (s) => addVertex(s, C),
      finishSketch,
    )
    expect(threePoints.finished).toBe(true)
  })

  it('bleibt beim Zurücksetzen im Modus', () => {
    const state = run(
      selectMode(IDLE, 'area'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      clearSketch,
    )
    expect(state).toEqual({ mode: 'area', points: [], cursor: null, finished: false })
  })

  it('folgt dem Zeiger nur, solange die Skizze offen ist', () => {
    const open = run(selectMode(IDLE, 'distance'), (s) => addVertex(s, A), (s) => moveCursor(s, B))
    expect(sketchPath(open)).toEqual([A, B])

    const closed = moveCursor(finishSketch(addVertex(open, B)), C)
    expect(closed.cursor).toBeNull()
  })
})

describe('measurementResult', () => {
  it('bleibt ohne Modus leer', () => {
    expect(measurementResult(IDLE)).toBeNull()
  })

  it('meldet eine Strecke erst ab dem zweiten Punkt als aussagekräftig', () => {
    const one = run(selectMode(IDLE, 'distance'), (s) => addVertex(s, A))
    expect(measurementResult(one)?.meaningful).toBe(false)
    expect(measurementResult(one)?.length).toBe(0)

    const two = addVertex(one, B)
    const result = measurementResult(two)
    expect(result?.meaningful).toBe(true)
    // Ein Hundertstel Grad Länge auf 52,5° Nord sind gut 680 m.
    expect(result?.length).toBeGreaterThan(600)
    expect(result?.area).toBeNull()
  })

  it('zählt den Zeiger als vorläufigen letzten Punkt mit', () => {
    const state = run(selectMode(IDLE, 'distance'), (s) => addVertex(s, A), (s) => addVertex(s, B))
    const settled = measurementResult(state)?.length ?? 0
    const withCursor = measurementResult(moveCursor(state, C))?.length ?? 0
    expect(withCursor).toBeGreaterThan(settled)
    // Gesetzte Punkte sind es trotzdem nur zwei.
    expect(measurementResult(moveCursor(state, C))?.vertexCount).toBe(2)
  })

  it('liefert für eine Fläche Flächeninhalt und geschlossenen Umfang', () => {
    const state = run(
      selectMode(IDLE, 'area'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      (s) => addVertex(s, C),
      finishSketch,
    )
    const result = measurementResult(state)
    expect(result?.area).toBeGreaterThan(0)
    // Der Umfang schließt den Rückweg zum ersten Punkt ein: mehr als die offene Kette.
    expect(result?.length).toBeGreaterThan(0)
    expect(result?.finished).toBe(true)
  })
})

describe('sketchFeatures', () => {
  function roles(state: MeasurementState): string[] {
    return sketchFeatures(state).features.map((feature) => String(feature.properties?.role))
  }

  it('zeichnet nichts, solange nichts gesetzt ist', () => {
    expect(sketchFeatures(selectMode(IDLE, 'distance')).features).toEqual([])
  })

  it('zeigt gesetzte Punkte und den Gummifaden getrennt', () => {
    const state = run(
      selectMode(IDLE, 'distance'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      (s) => moveCursor(s, C),
    )
    expect(roles(state)).toEqual(['line', 'pending', 'vertex', 'vertex'])
  })

  it('füllt die Fläche ab drei Punkten und schließt sie erst am Ende fest', () => {
    const open = run(
      selectMode(IDLE, 'area'),
      (s) => addVertex(s, A),
      (s) => addVertex(s, B),
      (s) => addVertex(s, C),
    )
    // Offen: der Rückweg zum ersten Punkt ist noch gestrichelt.
    expect(roles(open)).toEqual(['area', 'line', 'pending', 'vertex', 'vertex', 'vertex'])

    const closed = finishSketch(open)
    expect(roles(closed)).toEqual(['area', 'line', 'vertex', 'vertex', 'vertex'])

    const line = sketchFeatures(closed).features.find((f) => f.properties?.role === 'line')
    const geometry = line?.geometry
    expect(geometry?.type).toBe('LineString')
    // Vier Stützstellen für drei Punkte: der Ring ist geschlossen.
    if (geometry?.type === 'LineString') {
      expect(geometry.coordinates).toHaveLength(4)
    }
  })
})
