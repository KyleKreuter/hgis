import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { waitFor } from '@testing-library/react'
import { CLIENT_ID, PROJECT_VIEW_STATE_EVENT } from '@/api/events'
import { isRemoteSelection, useSelection } from '@/state/selection'
import { useLiveViewState } from '@/state/useLiveViewState'
import { FakeEventSource, installFakeEventSource } from '@/test/fakeEventSource'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { ViewStateDocument } from './viewState'

/**
 * What a live event actually does to the open project.
 *
 * The transport is a stand-in (`test/fakeEventSource.ts`), so what is shown here is our
 * own reaction to an event, not that a browser delivers one. The request that follows is
 * real, though: the stub answers `fetch`, so the query key, `api/client.ts` and the whole
 * read path are in the picture and a change to any of them fails this.
 */
describe('useLiveViewState', () => {
  const PROJECT = 'projekt-1'
  const LAYER = 'layer-1'

  function Probe({ layerId = LAYER as string | null, pendingWrite = false } = {}) {
    useLiveViewState(PROJECT, layerId, () => pendingWrite)
    return null
  }

  function documentWith(selection: number[]): ViewStateDocument {
    return {
      version: 1,
      activeLayerId: LAYER,
      layers: { [LAYER]: { sort: null, query: null, selection } },
    }
  }

  function announce(origin: string | null, version = 2) {
    const source = FakeEventSource.instances[FakeEventSource.instances.length - 1]
    source.emit(PROJECT_VIEW_STATE_EVENT, JSON.stringify({ projectId: PROJECT, version, origin }))
  }

  beforeEach(() => {
    installFakeEventSource()
    useSelection.setState({ layerId: null, selected: new Set() })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('hebt eine von außen gesetzte Auswahl auf der Karte hervor', async () => {
    stubFetch([{ match: 'view-state', body: documentWith([7, 8]) }])
    renderWithQueryClient(<Probe />)

    announce('anderer-tab')

    await waitFor(() => expect(useSelection.getState().selected).toEqual(new Set([7, 8])))
    expect(useSelection.getState().layerId).toBe(LAYER)
  })

  it('übernimmt auch eine geleerte Auswahl -- ein Ereignis meldet einen Zustand', async () => {
    useSelection.setState({ layerId: LAYER, selected: new Set([1, 2, 3]) })
    stubFetch([{ match: 'view-state', body: documentWith([]) }])
    renderWithQueryClient(<Probe />)

    announce('anderer-tab')

    await waitFor(() => expect(useSelection.getState().selected).toEqual(new Set()))
  })

  it('schreibt die übernommene Auswahl nicht zurück', async () => {
    stubFetch([{ match: 'view-state', body: documentWith([7]) }])
    // Genau die Frage, die `AttributeTable` beim Speichern stellt: kam diese Änderung vom
    // Nutzer? Ein "ja" hier wäre ein PUT, das die fremde Änderung als eigene zurückmeldet
    // -- und der Anfang einer Schleife.
    let remoteAtChange: boolean | null = null
    const unsubscribe = useSelection.subscribe(() => {
      remoteAtChange = isRemoteSelection()
    })
    renderWithQueryClient(<Probe />)

    announce('anderer-tab')

    await waitFor(() => expect(useSelection.getState().selected).toEqual(new Set([7])))
    unsubscribe()
    expect(remoteAtChange).toBe(true)
  })

  it('liest nichts nach, wenn das eigene Schreiben zurückkommt', async () => {
    const { calls } = stubFetch([{ match: 'view-state', body: documentWith([7]) }])
    renderWithQueryClient(<Probe />)

    announce(CLIENT_ID)

    // Kein Warten auf ein Ausbleiben: die Anfrage ginge im selben Tick los wie oben.
    await Promise.resolve()
    expect(calls).toHaveLength(0)
    expect(useSelection.getState().selected).toEqual(new Set())
  })

  it('lässt ein Ereignis eines anderen Projekts liegen', async () => {
    const { calls } = stubFetch([{ match: 'view-state', body: documentWith([7]) }])
    renderWithQueryClient(<Probe />)

    const source = FakeEventSource.instances[0]
    source.emit(
      PROJECT_VIEW_STATE_EVENT,
      JSON.stringify({ projectId: 'ein-anderes-projekt', version: 3, origin: 'anderer-tab' }),
    )

    await Promise.resolve()
    expect(calls).toHaveLength(0)
  })

  it('liest nach einer Wiederverbindung nach, weil in der Lücke nichts nachgeliefert wird', async () => {
    stubFetch([{ match: 'view-state', body: documentWith([5]) }])
    renderWithQueryClient(<Probe />)

    const source = FakeEventSource.instances[0]
    source.connect()
    // Die erste Verbindung liest nicht nach: was die Seite geöffnet hat, hat gerade geladen.
    await Promise.resolve()
    expect(useSelection.getState().selected).toEqual(new Set())

    source.connect()

    await waitFor(() => expect(useSelection.getState().selected).toEqual(new Set([5])))
  })

  it('bleibt nicht auf der Antwort des ersten Ereignisses stehen, wenn während dessen ein zweites kommt', async () => {
    // Der Zwischenspeicher beantwortet eine zweite gleiche Anfrage aus der ersten -- zu
    // Recht. Ohne die Nachlese danach bliebe der Stand des zweiten Ereignisses ungelesen,
    // und die Karte stünde dauerhaft eine Änderung zurück.
    const answers = [documentWith([1]), documentWith([2])]
    let releaseFirst: (() => void) | undefined
    let call = 0
    const answer = (body: ViewStateDocument) =>
      ({ ok: true, status: 200, json: () => Promise.resolve(body) }) as Response
    const fetchStub = vi.fn(() => {
      const body = answers[Math.min(call, answers.length - 1)]
      call += 1
      if (call > 1) return Promise.resolve(answer(body))
      return new Promise<Response>((resolve) => {
        releaseFirst = () => resolve(answer(body))
      })
    })
    vi.stubGlobal('fetch', fetchStub)
    renderWithQueryClient(<Probe />)

    announce('anderer-tab', 2)
    announce('anderer-tab', 3)
    releaseFirst?.()

    await waitFor(() => expect(useSelection.getState().selected).toEqual(new Set([2])))
    expect(fetchStub).toHaveBeenCalledTimes(2)
  })

  it('überschreibt nicht, was der Nutzer gerade getan und noch nicht gespeichert hat', async () => {
    // Der Stand auf dem Server ist in diesem Moment der ältere: die Auswahl des Nutzers
    // wartet noch auf ihr PUT. Sie jetzt durch die vom Server zu ersetzen hieße, ihm seine
    // Arbeit vom Bildschirm zu nehmen -- und einen Augenblick später wieder zurück.
    useSelection.setState({ layerId: LAYER, selected: new Set([42]) })
    const { calls } = stubFetch([{ match: 'view-state', body: documentWith([7]) }])
    renderWithQueryClient(<Probe pendingWrite />)

    announce('anderer-tab')

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(useSelection.getState().selected).toEqual(new Set([42]))
  })

  it('liest auch ohne offenen Layer nach, hebt aber nichts hervor', async () => {
    // Der Stand landet im Zwischenspeicher, damit der nächste geöffnete Layer ihn dort
    // vorfindet. Eine Hervorhebung gäbe es nicht: eine fid bedeutet nichts ohne ihren Layer.
    const { calls } = stubFetch([{ match: 'view-state', body: documentWith([7]) }])
    renderWithQueryClient(<Probe layerId={null} />)

    announce('anderer-tab')

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(useSelection.getState().selected).toEqual(new Set())
  })

  it('schließt den Strom, wenn das Projekt verlassen wird', () => {
    stubFetch([{ match: 'view-state', body: documentWith([]) }])
    const { unmount } = renderWithQueryClient(<Probe />)

    unmount()

    expect(FakeEventSource.instances[0].closed).toBe(true)
  })

  it('übergeht eine fehlgeschlagene Nachlese und liest beim nächsten Ereignis wieder', async () => {
    const { calls, fetchStub } = stubFetch([{ match: 'view-state', body: { detail: 'kaputt' }, status: 500 }])
    renderWithQueryClient(<Probe />)

    announce('anderer-tab')
    await waitFor(() => expect(calls).toHaveLength(1))
    expect(useSelection.getState().selected).toEqual(new Set())

    // Der zweite Anlauf gelingt: der Fehler darf den Kanal nicht dauerhaft lahmlegen.
    fetchStub.mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(documentWith([3])),
    } as Response)
    announce('anderer-tab', 3)

    await waitFor(() => expect(useSelection.getState().selected).toEqual(new Set([3])))
  })
})
