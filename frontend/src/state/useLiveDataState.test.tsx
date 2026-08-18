import { describe, expect, it, vi } from 'vitest'
import { act, waitFor } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { layerKeys } from '@/api/layers'
import type { LayerSummary } from '@/api/layers'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import { DATA_STATE_SETTLE_MS, useLiveDataState, type LiveDataState } from './useLiveDataState'

/**
 * `notify` stands in for a data-state event that already passed `useLiveViewState`'s own
 * scoping and origin filter -- see that hook's own tests for those. What is worth proving
 * here is what happens once one arrives: the debounce, the refetch, and the one thing this
 * hook says anything about, a vanished active layer.
 */
describe('useLiveDataState', () => {
  const PROJECT = 'projekt-1'
  const ACTIVE = 'layer-1'
  const OTHER = 'layer-2'
  const LAYERS_URL = `/projects/${PROJECT}/layers`

  function summary(id: string, name: string): LayerSummary {
    return {
      id,
      name,
      geometryType: 'MULTIPOLYGON',
      srid: 25832,
      featureCount: 1,
      visible: true,
      zIndex: 0,
      minZoom: 0,
      maxZoom: 22,
      dataVersion: 1,
      styleVersion: 1,
      extent: null,
    }
  }

  let probeApi: LiveDataState | null = null

  function Probe({
    activeLayerId = ACTIVE as string | null,
    onActiveLayerDeleted = () => {},
    workAtRisk = false,
  }: {
    activeLayerId?: string | null
    onActiveLayerDeleted?: (layer: Pick<LayerSummary, 'id' | 'name'>) => void
    workAtRisk?: boolean
  } = {}) {
    probeApi = useLiveDataState(PROJECT, { activeLayerId, onActiveLayerDeleted, workAtRisk })
    return null
  }

  /** Well past the settle window -- bound to its value so a later change does not
   *  silently make these tests meaningless. */
  const pastTheSettleWindow = () =>
    new Promise((resolve) => setTimeout(resolve, DATA_STATE_SETTLE_MS + 150))

  /**
   * Unlike `test/render.tsx`'s own `testQueryClient`, this keeps a query's data around
   * after `setQueryData` even without an active observer. `useLiveDataState` never
   * mounts a `useQuery` for the layer list itself -- it only reads and writes the cache
   * imperatively -- so `testQueryClient`'s `gcTime: 0` would garbage-collect a "before"
   * list seeded for a test before the hook's settle window even elapses, since nothing
   * here holds the entry open the way the real `Workspace` route's own `useQuery` does.
   */
  function clientKeepingSeededData(): QueryClient {
    return new QueryClient({ defaultOptions: { queries: { retry: false } } })
  }

  it('lädt nicht sofort -- erst nach dem Sammelfenster', async () => {
    const { calls } = stubFetch([{ match: LAYERS_URL, body: [] }])
    renderWithQueryClient(<Probe />)

    act(() => probeApi!.notify())

    expect(calls).toHaveLength(0)
    await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })
  })

  it('sammelt mehrere Ereignisse zu einer einzigen Anfrage', async () => {
    const { calls } = stubFetch([{ match: LAYERS_URL, body: [] }])
    renderWithQueryClient(<Probe />)

    // Jeder Aufruf verschiebt die Frist -- drei kurz hintereinander dürfen nicht drei
    // Anfragen ergeben, sonst wäre das Fenster wirkungslos.
    act(() => probeApi!.notify())
    await new Promise((resolve) => setTimeout(resolve, DATA_STATE_SETTLE_MS / 2))
    act(() => probeApi!.notify())
    await new Promise((resolve) => setTimeout(resolve, DATA_STATE_SETTLE_MS / 2))
    act(() => probeApi!.notify())

    await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })
    await pastTheSettleWindow()
    expect(calls).toHaveLength(1)
  })

  it('erkennt einen verschwundenen aktiven Layer und meldet ihn mit seinem Namen', async () => {
    const client = clientKeepingSeededData()
    client.setQueryData(layerKeys.list(PROJECT), [summary(ACTIVE, 'Kanäle'), summary(OTHER, 'Schächte')])
    const onActiveLayerDeleted = vi.fn()
    stubFetch([{ match: LAYERS_URL, body: [summary(OTHER, 'Schächte')] }])
    renderWithQueryClient(<Probe activeLayerId={ACTIVE} onActiveLayerDeleted={onActiveLayerDeleted} />, client)

    act(() => probeApi!.notify())

    await waitFor(
      () =>
        expect(onActiveLayerDeleted).toHaveBeenCalledWith(
          expect.objectContaining({ id: ACTIVE, name: 'Kanäle' }),
        ),
      { timeout: 2000 },
    )
    expect(onActiveLayerDeleted).toHaveBeenCalledTimes(1)
  })

  it('meldet nichts, wenn der aktive Layer im neuen Stand noch existiert', async () => {
    const client = clientKeepingSeededData()
    client.setQueryData(layerKeys.list(PROJECT), [summary(ACTIVE, 'Kanäle')])
    const onActiveLayerDeleted = vi.fn()
    const { calls } = stubFetch([{ match: LAYERS_URL, body: [summary(ACTIVE, 'Kanäle')] }])
    renderWithQueryClient(<Probe activeLayerId={ACTIVE} onActiveLayerDeleted={onActiveLayerDeleted} />, client)

    act(() => probeApi!.notify())

    await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })
    await pastTheSettleWindow()
    expect(onActiveLayerDeleted).not.toHaveBeenCalled()
  })

  it('meldet nichts ohne offenen Layer', async () => {
    const onActiveLayerDeleted = vi.fn()
    const { calls } = stubFetch([{ match: LAYERS_URL, body: [] }])
    renderWithQueryClient(<Probe activeLayerId={null} onActiveLayerDeleted={onActiveLayerDeleted} />)

    act(() => probeApi!.notify())

    await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })
    await pastTheSettleWindow()
    expect(onActiveLayerDeleted).not.toHaveBeenCalled()
  })

  it('meldet nichts, wenn von diesem Layer vorher schon nichts im Zwischenspeicher stand', async () => {
    // Ohne einen vorherigen Stand ist der Name nicht zu ermitteln -- eine Meldung wäre
    // hier eine Behauptung ohne Grundlage, kein bloß unbenannter Layer.
    const onActiveLayerDeleted = vi.fn()
    const { calls } = stubFetch([{ match: LAYERS_URL, body: [] }])
    renderWithQueryClient(<Probe activeLayerId={ACTIVE} onActiveLayerDeleted={onActiveLayerDeleted} />)

    act(() => probeApi!.notify())

    await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })
    await pastTheSettleWindow()
    expect(onActiveLayerDeleted).not.toHaveBeenCalled()
  })

  it('meldet nichts, wenn dieses Fenster den Layer inzwischen selbst verlassen hat', async () => {
    // Gefunden an `readBackOnce`s eigenem "checked again on arrival": eine Antwort, die
    // eintrifft, nachdem der Nutzer längst weitergezogen ist, darf nicht rückwirkend
    // dessen neue Wahl zunichtemachen.
    const client = clientKeepingSeededData()
    client.setQueryData(layerKeys.list(PROJECT), [summary(ACTIVE, 'Kanäle'), summary(OTHER, 'Schächte')])
    const onActiveLayerDeleted = vi.fn()
    const { calls } = stubFetch([
      { match: LAYERS_URL, body: [summary(OTHER, 'Schächte')], delayMs: 80 },
    ])
    const view = renderWithQueryClient(
      <Probe activeLayerId={ACTIVE} onActiveLayerDeleted={onActiveLayerDeleted} />,
      client,
    )

    act(() => probeApi!.notify())
    await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })

    // Der Nutzer öffnet einen anderen Layer, während die verzögerte Antwort noch unterwegs ist.
    act(() => view.rerender(<Probe activeLayerId={OTHER} onActiveLayerDeleted={onActiveLayerDeleted} />))

    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(onActiveLayerDeleted).not.toHaveBeenCalled()
  })

  it('übergeht einen fehlgeschlagenen Reload, statt zu werfen', async () => {
    const client = clientKeepingSeededData()
    client.setQueryData(layerKeys.list(PROJECT), [summary(ACTIVE, 'Kanäle')])
    const onActiveLayerDeleted = vi.fn()
    stubFetch([{ match: LAYERS_URL, body: { detail: 'kaputt' }, status: 500 }])
    renderWithQueryClient(<Probe activeLayerId={ACTIVE} onActiveLayerDeleted={onActiveLayerDeleted} />, client)

    expect(() => act(() => probeApi!.notify())).not.toThrow()

    await pastTheSettleWindow()
    expect(onActiveLayerDeleted).not.toHaveBeenCalled()
  })

  it('lädt nach dem Verlassen des Projekts nicht mehr nach', async () => {
    const { calls } = stubFetch([{ match: LAYERS_URL, body: [] }])
    const view = renderWithQueryClient(<Probe />)

    act(() => probeApi!.notify())
    view.unmount()

    await pastTheSettleWindow()
    expect(calls).toHaveLength(0)
  })

  /**
   * Vom Prüfer gefunden: `activeVectorLayer` in `projects.$projectId.tsx` wird direkt
   * aus der Layerliste berechnet, und `DrawController`/`AttributeTable` haengen genau
   * daran. Ein Reload, der den aktiven Layer verschwinden liesse, wuerde die
   * Zeichenoberflaeche also austragen, noch bevor `onActiveLayerDeleted` oder
   * `leaveGuard` ueberhaupt zum Zug kaemen. `workAtRisk` haelt den Reload deshalb
   * komplett zurueck, nicht nur dessen Folge.
   */
  describe('workAtRisk', () => {
    it('lädt nicht nach, solange Arbeit auf dem Spiel steht', async () => {
      const { calls } = stubFetch([{ match: LAYERS_URL, body: [] }])
      renderWithQueryClient(<Probe workAtRisk />)

      act(() => probeApi!.notify())

      await pastTheSettleWindow()
      expect(calls).toHaveLength(0)
    })

    it('holt eine zurückgehaltene Aktualisierung nach, sobald nichts mehr auf dem Spiel steht', async () => {
      const { calls } = stubFetch([{ match: LAYERS_URL, body: [] }])
      const view = renderWithQueryClient(<Probe workAtRisk />)

      act(() => probeApi!.notify())
      await pastTheSettleWindow()
      expect(calls).toHaveLength(0)

      act(() => view.rerender(<Probe workAtRisk={false} />))

      await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })
    })

    it('erkennt einen geloeschten aktiven Layer erst, nachdem die Arbeit gesichert wurde', async () => {
      const client = clientKeepingSeededData()
      client.setQueryData(layerKeys.list(PROJECT), [summary(ACTIVE, 'Kanäle')])
      const onActiveLayerDeleted = vi.fn()
      stubFetch([{ match: LAYERS_URL, body: [] }])
      const view = renderWithQueryClient(
        <Probe activeLayerId={ACTIVE} onActiveLayerDeleted={onActiveLayerDeleted} workAtRisk />,
        client,
      )

      act(() => probeApi!.notify())
      await pastTheSettleWindow()
      expect(onActiveLayerDeleted).not.toHaveBeenCalled()

      act(() =>
        view.rerender(
          <Probe
            activeLayerId={ACTIVE}
            onActiveLayerDeleted={onActiveLayerDeleted}
            workAtRisk={false}
          />,
        ),
      )

      await waitFor(
        () =>
          expect(onActiveLayerDeleted).toHaveBeenCalledWith(
            expect.objectContaining({ id: ACTIVE, name: 'Kanäle' }),
          ),
        { timeout: 2000 },
      )
    })

    it('lädt nicht doppelt nach, wenn mehrere Ereignisse zurückgehalten wurden', async () => {
      const { calls } = stubFetch([{ match: LAYERS_URL, body: [] }])
      const view = renderWithQueryClient(<Probe workAtRisk />)

      act(() => probeApi!.notify())
      await pastTheSettleWindow()
      act(() => probeApi!.notify())
      await pastTheSettleWindow()
      expect(calls).toHaveLength(0)

      act(() => view.rerender(<Probe workAtRisk={false} />))

      await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })
      // Kein zweiter Nachschlag für das zweite zurückgehaltene Ereignis -- ein
      // zurückgehaltener Reload sagt bereits "seitdem hat sich etwas geändert",
      // ein zweiter davor sagt nichts mehr dazu.
      await pastTheSettleWindow()
      expect(calls).toHaveLength(1)
    })
  })

  /**
   * Vom Prüfer gefunden (package 2): `heatmapFieldRangeQuery`s Zwischenspeicher hatte
   * vor diesem Fix keinen einzigen Schreibweg, der ihn frühzeitig für ungültig erklärt --
   * nur die eigene Fünf-Minuten-`staleTime`. Ein fremder Schreibvorgang, der ein Feld
   * über eine gesetzte Grenze hinaus verschiebt, blieb dadurch für die Legende und für
   * `heatmapWeight` unsichtbar, bis das Fenster von selbst ablief.
   */
  describe('Feldspanne bei Datenänderung (heatmapFieldRangeQuery)', () => {
    const CLASSIFY_KEY = layerKeys.classify(ACTIVE, 'wert', 'quantile', 12)

    function isInvalidated(client: QueryClient, queryKey: readonly unknown[]): boolean {
      const query = client.getQueryCache().find({ queryKey, exact: true })
      if (!query) throw new Error(`Kein Eintrag für ${JSON.stringify(queryKey)}`)
      return query.isStale()
    }

    it('erklärt die Feldspanne eines Layers für ungültig, dessen dataVersion sich geändert hat', async () => {
      const client = clientKeepingSeededData()
      client.setQueryData(layerKeys.list(PROJECT), [{ ...summary(ACTIVE, 'Kanäle'), dataVersion: 1 }])
      client.setQueryData(CLASSIFY_KEY, {})
      stubFetch([{ match: LAYERS_URL, body: [{ ...summary(ACTIVE, 'Kanäle'), dataVersion: 2 }] }])
      renderWithQueryClient(<Probe activeLayerId={null} />, client)

      act(() => probeApi!.notify())

      await waitFor(() => expect(isInvalidated(client, CLASSIFY_KEY)).toBe(true), { timeout: 2000 })
    })

    it('lässt die Feldspanne eines Layers mit unveränderter dataVersion in Ruhe', async () => {
      const client = clientKeepingSeededData()
      client.setQueryData(layerKeys.list(PROJECT), [{ ...summary(ACTIVE, 'Kanäle'), dataVersion: 1 }])
      client.setQueryData(CLASSIFY_KEY, {})
      const { calls } = stubFetch([{ match: LAYERS_URL, body: [{ ...summary(ACTIVE, 'Kanäle'), dataVersion: 1 }] }])
      renderWithQueryClient(<Probe activeLayerId={null} />, client)

      act(() => probeApi!.notify())

      await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })
      await pastTheSettleWindow()
      expect(isInvalidated(client, CLASSIFY_KEY)).toBe(false)
    })

    /**
     * Ein Layer, der im vorherigen Stand noch gar nicht bekannt war (neu erschienen, oder
     * die Liste war zuvor gar nicht geladen), hat keinen Zwischenspeicher, den es zu
     * invalidieren gäbe -- `previousVersions.get(...)` liefert `undefined`, und das darf
     * nicht als "hat sich geändert" gelesen werden.
     */
    it('wirft nicht, wenn ein Layer im vorherigen Stand noch nicht bekannt war', async () => {
      const client = clientKeepingSeededData()
      client.setQueryData(layerKeys.list(PROJECT), [])
      const { calls } = stubFetch([{ match: LAYERS_URL, body: [summary(ACTIVE, 'Kanäle')] }])
      renderWithQueryClient(<Probe activeLayerId={null} />, client)

      expect(() => act(() => probeApi!.notify())).not.toThrow()

      await waitFor(() => expect(calls).toHaveLength(1), { timeout: 2000 })
    })
  })
})
