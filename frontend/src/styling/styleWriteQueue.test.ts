import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createStyleWriteQueue, DEFER_MS, type StyleWrite } from './styleWriteQueue'
import type { LayerStyle } from './types'

function styleOf(color: string): LayerStyle {
  return {
    version: 1,
    renderer: {
      type: 'single',
      symbol: { kind: 'line', color, width: 1 },
    },
    opacity: 1,
  }
}

describe('createStyleWriteQueue', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('schreibt eine nicht zurückgestellte Änderung sofort', () => {
    const written: StyleWrite[] = []
    const queue = createStyleWriteQueue((write) => written.push(write))

    queue.queue({ layerId: 'a', style: styleOf('#ff0000') })

    expect(written).toEqual([{ layerId: 'a', style: styleOf('#ff0000') }])
  })

  it('hält eine zurückgestellte Änderung bis zum Ablauf der Wartezeit zurück', () => {
    const written: StyleWrite[] = []
    const queue = createStyleWriteQueue((write) => written.push(write))

    queue.queue({ layerId: 'a', style: styleOf('#ff0000') }, { defer: true })
    vi.advanceTimersByTime(DEFER_MS - 1)
    expect(written).toEqual([])

    vi.advanceTimersByTime(1)
    expect(written).toHaveLength(1)
  })

  it('fasst einen Zug am Farbwähler zu einem einzigen Schreibvorgang zusammen', () => {
    const written: StyleWrite[] = []
    const queue = createStyleWriteQueue((write) => written.push(write))

    queue.queue({ layerId: 'a', style: styleOf('#ff0000') }, { defer: true })
    vi.advanceTimersByTime(100)
    queue.queue({ layerId: 'a', style: styleOf('#00ff00') }, { defer: true })
    vi.advanceTimersByTime(100)
    queue.queue({ layerId: 'a', style: styleOf('#0000ff') }, { defer: true })
    vi.advanceTimersByTime(DEFER_MS)

    // Nur der zuletzt gesehene Wert, und nur einmal -- die Zwischenwerte beschreiben
    // einen Stil, über den der Benutzer bereits hinweg ist.
    expect(written).toEqual([{ layerId: 'a', style: styleOf('#0000ff') }])
  })

  it('schreibt einen zurückgestellten Stil auf den Layer, zu dem er gehört -- nicht auf den gerade aktiven', () => {
    const written: StyleWrite[] = []
    const queue = createStyleWriteQueue((write) => written.push(write))

    // Genau der belegte Fehler: Farbe in Layer A setzen, innerhalb der Wartezeit auf
    // Layer B wechseln. Der Wechsel leert die Warteschlange (`useStyleEditor` ruft dazu
    // `flush` im Aufräumschritt seines Layer-Effekts), und der Schreibvorgang muss auf A
    // landen, weil er A beschreibt.
    queue.queue({ layerId: 'a', style: styleOf('#ff0000') }, { defer: true })
    queue.flush()

    expect(written).toEqual([{ layerId: 'a', style: styleOf('#ff0000') }])

    // Danach ist nichts mehr offen: der Zeitgeber darf denselben Stil nicht ein zweites
    // Mal schicken, jetzt womöglich gegen B.
    queue.queue({ layerId: 'b', style: styleOf('#00ff00') }, { defer: true })
    vi.advanceTimersByTime(DEFER_MS)
    expect(written).toEqual([
      { layerId: 'a', style: styleOf('#ff0000') },
      { layerId: 'b', style: styleOf('#00ff00') },
    ])
  })

  it('bricht eine wartende Änderung ab, wenn eine sofortige folgt', () => {
    const written: StyleWrite[] = []
    const queue = createStyleWriteQueue((write) => written.push(write))

    queue.queue({ layerId: 'a', style: styleOf('#ff0000') }, { defer: true })
    queue.queue({ layerId: 'a', style: null })
    vi.advanceTimersByTime(DEFER_MS)

    expect(written).toEqual([{ layerId: 'a', style: null }])
  })

  it('verwirft eine wartende Änderung, ohne sie zu schreiben -- auch bei späterem Leeren', () => {
    const written: StyleWrite[] = []
    const queue = createStyleWriteQueue((write) => written.push(write))

    queue.queue({ layerId: 'a', style: styleOf('#ff0000') }, { defer: true })
    queue.drop()
    vi.advanceTimersByTime(DEFER_MS)

    // Der Zeitgeber allein zu stoppen reicht nicht: der Layerwechsel leert die
    // Warteschlange, und ein bloß nicht mehr eingeplanter Schreibvorgang ginge dann
    // doch noch raus -- mit einem Stil, den `isPersistable` gerade abgelehnt hat.
    queue.flush()

    expect(written).toEqual([])
  })

  it('schreibt bei leerer Warteschlange nichts', () => {
    const commit = vi.fn()
    const queue = createStyleWriteQueue(commit)

    queue.flush()
    queue.drop()
    queue.flush()

    expect(commit).not.toHaveBeenCalled()
  })

  it('nimmt eine Änderung an, die aus dem Schreibvorgang selbst kommt', () => {
    const written: StyleWrite[] = []
    // `commit` schreibt in den Query-Cache, das Panel rendert neu und kann dabei die
    // nächste Änderung einstellen. Sie darf nicht verloren gehen.
    const queue: StyleWriteQueueUnderTest = { current: null }
    queue.current = createStyleWriteQueue((write) => {
      written.push(write)
      if (written.length === 1) {
        queue.current?.queue({ layerId: 'a', style: styleOf('#00ff00') }, { defer: true })
      }
    })

    queue.current.queue({ layerId: 'a', style: styleOf('#ff0000') })
    vi.advanceTimersByTime(DEFER_MS)

    expect(written).toEqual([
      { layerId: 'a', style: styleOf('#ff0000') },
      { layerId: 'a', style: styleOf('#00ff00') },
    ])
  })
})

/** Only so the recursive test above can reach the queue from inside its own `commit`. */
interface StyleWriteQueueUnderTest {
  current: ReturnType<typeof createStyleWriteQueue> | null
}
