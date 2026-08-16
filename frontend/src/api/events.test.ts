import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  CLIENT_ID,
  PROJECT_VIEW_STATE_EVENT,
  connectLiveChannel,
  parseProjectViewState,
  reconnectDelay,
  shouldReadBack,
  type ProjectViewStateEvent,
} from './events'
import { FakeEventSource, installFakeEventSource } from '@/test/fakeEventSource'

/** The longest `reconnectDelay(0, …)` can ever be -- the jitter halves it at most. */
const LONGEST_FIRST_WAIT = 2000

describe('parseProjectViewState', () => {
  it('liest Projekt, Version und Urheber', () => {
    const event = parseProjectViewState('{"projectId":"p-1","version":42,"origin":"tab-a"}')

    expect(event).toEqual({ projectId: 'p-1', version: 42, origin: 'tab-a' })
  })

  it('nimmt ein Ereignis ohne Urheber an -- niemand muss sich benennen', () => {
    expect(parseProjectViewState('{"projectId":"p-1","version":1,"origin":null}')?.origin).toBeNull()
    expect(parseProjectViewState('{"projectId":"p-1","version":1}')?.origin).toBeNull()
  })

  it.each([
    ['kein JSON', 'nicht-json'],
    ['kein Objekt', '"p-1"'],
    ['null', 'null'],
    ['ohne Projekt', '{"version":1}'],
    ['ohne Version', '{"projectId":"p-1"}'],
    ['Version als Text', '{"projectId":"p-1","version":"42"}'],
    ['Urheber als Zahl', '{"projectId":"p-1","version":1,"origin":7}'],
  ])('verwirft %s, statt zu werfen', (_case, data) => {
    expect(parseProjectViewState(data)).toBeNull()
  })
})

describe('shouldReadBack', () => {
  const event = (over: Partial<ProjectViewStateEvent> = {}): ProjectViewStateEvent => ({
    projectId: 'p-1',
    version: 2,
    origin: null,
    ...over,
  })

  it('liest nach, wenn ein anderer Client das Projekt geändert hat', () => {
    expect(shouldReadBack(event({ origin: 'tab-b' }), { projectId: 'p-1', clientId: 'tab-a' })).toBe(true)
  })

  it('liest nicht nach, wenn der eigene Schreibvorgang zurückkommt', () => {
    expect(shouldReadBack(event({ origin: 'tab-a' }), { projectId: 'p-1', clientId: 'tab-a' })).toBe(false)
  })

  it('liest nicht nach für ein fremdes Projekt', () => {
    expect(shouldReadBack(event({ projectId: 'p-2' }), { projectId: 'p-1', clientId: 'tab-a' })).toBe(false)
  })

  it('liest nach, wenn niemand als Urheber genannt ist', () => {
    // Ein Schreiber ohne Namen -- etwa curl -- ist für jeden anderen eine fremde Änderung.
    expect(shouldReadBack(event({ origin: null }), { projectId: 'p-1', clientId: 'tab-a' })).toBe(true)
  })
})

describe('reconnectDelay', () => {
  it('verdoppelt sich mit jedem Fehlversuch', () => {
    expect(reconnectDelay(0, 1)).toBe(2000)
    expect(reconnectDelay(1, 1)).toBe(4000)
    expect(reconnectDelay(2, 1)).toBe(8000)
  })

  it('wächst nicht über eine Minute hinaus', () => {
    expect(reconnectDelay(50, 1)).toBe(60_000)
  })

  it('streut zwischen der Hälfte und dem vollen Wert, damit nicht alle Tabs gleichzeitig wiederkommen', () => {
    expect(reconnectDelay(0, 0)).toBe(1000)
    expect(reconnectDelay(0, 1)).toBe(2000)
  })
})

describe('CLIENT_ID', () => {
  it('ist ein Name, den der Server als Header annimmt', () => {
    expect(CLIENT_ID).toMatch(/^[A-Za-z0-9_-]{1,64}$/)
  })
})

/**
 * Das Verhalten gegenüber einem `EventSource`-Ersatz, nicht gegenüber einem Browser --
 * siehe `test/fakeEventSource.ts` für das, was hier nachweislich *nicht* geprüft wird.
 */
describe('connectLiveChannel', () => {
  beforeEach(() => {
    installFakeEventSource()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  const latest = () => FakeEventSource.instances[FakeEventSource.instances.length - 1]

  it('öffnet den Kanal beim Aufruf', () => {
    const close = connectLiveChannel({})

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(latest().url).toBe('/api/events')
    close()
  })

  it('reicht ein Ereignis gelesen weiter', () => {
    const onProjectViewState = vi.fn()
    const close = connectLiveChannel({ onProjectViewState })

    latest().emit(PROJECT_VIEW_STATE_EVENT, '{"projectId":"p-1","version":9,"origin":null}')

    expect(onProjectViewState).toHaveBeenCalledWith({ projectId: 'p-1', version: 9, origin: null })
    close()
  })

  it('übergeht ein unlesbares Ereignis, statt den Kanal zu verlieren', () => {
    const onProjectViewState = vi.fn()
    const close = connectLiveChannel({ onProjectViewState })

    expect(() => latest().emit(PROJECT_VIEW_STATE_EVENT, '{kaputt')).not.toThrow()
    expect(onProjectViewState).not.toHaveBeenCalled()

    latest().emit(PROJECT_VIEW_STATE_EVENT, '{"projectId":"p-1","version":9}')
    expect(onProjectViewState).toHaveBeenCalledTimes(1)
    close()
  })

  it('meldet die erste Verbindung als erste und jede weitere als Wiederverbindung', () => {
    const onOpen = vi.fn()
    const close = connectLiveChannel({ onOpen })

    latest().connect()
    expect(onOpen).toHaveBeenLastCalledWith(false)

    latest().fail({ fatal: true })
    vi.advanceTimersByTime(LONGEST_FIRST_WAIT)
    latest().connect()

    expect(onOpen).toHaveBeenLastCalledWith(true)
    close()
  })

  it('lässt den Browser selbst neu verbinden, solange er es noch versucht', () => {
    const close = connectLiveChannel({})

    latest().fail({ fatal: false })
    vi.advanceTimersByTime(60_000)

    expect(FakeEventSource.instances).toHaveLength(1)
    close()
  })

  it('verbindet selbst neu, wenn der Browser aufgegeben hat', () => {
    const close = connectLiveChannel({})

    latest().fail({ fatal: true })
    expect(FakeEventSource.instances).toHaveLength(1)

    vi.advanceTimersByTime(LONGEST_FIRST_WAIT)

    expect(FakeEventSource.instances).toHaveLength(2)
    close()
  })

  it('wartet nach jedem weiteren Fehlversuch länger', () => {
    const close = connectLiveChannel({})

    latest().fail({ fatal: true })
    vi.advanceTimersByTime(LONGEST_FIRST_WAIT)
    expect(FakeEventSource.instances).toHaveLength(2)

    // Zweiter Fehlversuch ohne zwischenzeitliche Verbindung: die kürzeste mögliche
    // Wartezeit ist jetzt so lang wie die längste des ersten.
    latest().fail({ fatal: true })
    vi.advanceTimersByTime(LONGEST_FIRST_WAIT - 1)
    expect(FakeEventSource.instances).toHaveLength(2)

    vi.advanceTimersByTime(LONGEST_FIRST_WAIT + 1)
    expect(FakeEventSource.instances).toHaveLength(3)
    close()
  })

  it('beginnt nach einer geglückten Verbindung wieder mit der kurzen Wartezeit', () => {
    const close = connectLiveChannel({})

    latest().fail({ fatal: true })
    vi.advanceTimersByTime(LONGEST_FIRST_WAIT)
    latest().connect()

    latest().fail({ fatal: true })
    vi.advanceTimersByTime(LONGEST_FIRST_WAIT)

    expect(FakeEventSource.instances).toHaveLength(3)
    close()
  })

  it('öffnet für mehrere Fehlermeldungen desselben Versuchs nur einen Kanal', () => {
    const close = connectLiveChannel({})

    latest().fail({ fatal: true })
    latest().fail({ fatal: true })
    latest().fail({ fatal: true })
    vi.advanceTimersByTime(60_000)

    expect(FakeEventSource.instances).toHaveLength(2)
    close()
  })

  it('schließt den Kanal und verbindet danach nicht mehr', () => {
    const close = connectLiveChannel({})
    const source = latest()

    close()

    expect(source.closed).toBe(true)
    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('verbindet nach dem Schließen auch dann nicht neu, wenn noch eine Wartezeit lief', () => {
    const close = connectLiveChannel({})

    latest().fail({ fatal: true })
    close()
    vi.advanceTimersByTime(60_000)

    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('kommt ohne EventSource aus, statt die Seite mitzureißen', () => {
    vi.stubGlobal('EventSource', undefined)

    const close = connectLiveChannel({})

    expect(FakeEventSource.instances).toHaveLength(0)
    expect(() => close()).not.toThrow()
  })
})
