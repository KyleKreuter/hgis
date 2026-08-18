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
  }: {
    activeLayerId?: string | null
    onActiveLayerDeleted?: (layer: Pick<LayerSummary, 'id' | 'name'>) => void
  } = {}) {
    probeApi = useLiveDataState(PROJECT, { activeLayerId, onActiveLayerDeleted })
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
})
