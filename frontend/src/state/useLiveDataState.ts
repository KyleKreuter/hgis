import { useEffect, useRef } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { layerKeys, layerListQuery, type LayerSummary } from '@/api/layers'

/**
 * How long this hook waits after the last data-state event before refetching the layer
 * catalog.
 *
 * An import of a few thousand objects followed by a style change is not one write but
 * several -- create the layer, finish the import, set the style -- and the first two of
 * those can themselves take real time on the server. Refetching after every single one
 * would ask for the same catalog up to three times over a few seconds and, since a
 * refresh is also what hands `MapLayerSync` a new tile URL for every layer on the map
 * (`map/layerSpecs.ts#buildTileUrl`), redraw the whole map that many times too --
 * contract section 2.2's "die Karte flackert".
 *
 * Longer than `JUMP_SETTLE_MS` (300 ms, see this file's sibling) on purpose: nothing
 * here moves the camera, so there is no view stuck mid-jump to keep short for, only a
 * background fetch to spare -- and a burst of writes *to* a layer plausibly spans a
 * couple of seconds of real import/render work, not the couple of clicks
 * `JUMP_SETTLE_MS` was tuned against. Still short enough that the ordinary case -- one
 * isolated change -- reaches the screen well under a second after it happened, which is
 * what keeps it from "feeling dead" (contract section 2.2).
 */
export const DATA_STATE_SETTLE_MS = 500

export interface LiveDataStateOptions {
  /** The layer this window currently has open, or null. */
  activeLayerId: string | null
  /**
   * The active layer no longer exists in the freshly refetched catalog -- someone else
   * deleted it while this window had it open. Called with the layer as it was last seen
   * (its id and name are the only things worth saying); what "closing" it means for the
   * rest of the workspace is the caller's decision, not this hook's -- see
   * `projects.$projectId.tsx`'s `handleActiveLayerDeleted`.
   */
  onActiveLayerDeleted: (layer: Pick<LayerSummary, 'id' | 'name'>) => void
  /**
   * Whether this session would lose something by having the layer catalog refreshed
   * right now -- the same question, and the same value, `useDeferredLayerJump` already
   * asks (`hasUnsavedWork`: buffered map or table edits, or a shape mid-sketch).
   *
   * Found by the Prüfer: `activeVectorLayer` in `projects.$projectId.tsx` is computed
   * straight from the layer list query (`layers.find(...)`), and `DrawController` and
   * `AttributeTable` are both mounted on it being non-null. A refresh that drops the
   * active layer therefore unmounts whichever of the two is running -- clearing the
   * drawing surface and ending the buffer's only visible way back -- the instant the
   * fetch lands, before `onActiveLayerDeleted` or `leaveGuard` ever get a turn to ask
   * anything. `deleteLocked` (`DeleteLayerDialog.tsx`) is the same protection for the
   * *local* delete, built on state that exists only in this tab; there is no such lock
   * across tabs, which is exactly why a remote delete needs one here.
   *
   * While true, a refresh that arrives is held rather than run -- not dropped: the
   * moment this flips back to false (the buffer saved or discarded, the sketch finished
   * or abandoned), the held refresh runs on its own, the same shape as
   * `useDeferredLayerJump`'s own catch-up effect. The cost is the one the Prüfer named
   * outright: a remote change is not seen until this session's own work is settled,
   * which for a long session is a long wait -- accepted for the same reason
   * `hasPendingWrite` already makes `useLiveViewState` wait rather than overwrite a
   * write in flight.
   */
  workAtRisk: boolean
}

export interface LiveDataState {
  /** Something about this project's data changed; refetch after the settle window. */
  notify: () => void
}

/**
 * Keeps the layer catalog in step with whoever else is writing to this project's data.
 *
 * The channel only ever reports that *something* moved (`api/events.ts`'s
 * `PROJECT_CATALOG_EVENT`, forwarded here through `useLiveViewState`'s single
 * connection -- see the comment on its own `onProjectDataState` option for why this
 * shares that connection instead of opening a second one). Reacting to it is, on
 * purpose, almost nothing: invalidate the layer list query. `MapLayerSync`
 * (`map/syncLayers.ts`) already diffs whatever that query holds against the map on
 * every render, and the tile URL it builds already carries the version numbers that
 * make a changed layer fetch new tiles by itself -- so a refetch is the whole fix, and
 * nothing here touches the map, a paint property or a camera directly (contract section
 * 2.1, "Mehr soll nicht passieren"). See {@link LiveDataStateOptions.workAtRisk} for the
 * one condition under which even that refetch is held back.
 *
 * The one thing worth more than silence: the layer this window has *open* turning out
 * to be gone. Every other change to the catalog -- a layer appearing, a layer nobody has
 * open disappearing, a tile redrawing under an unchanged style -- is exactly the kind of
 * "state, not change" this whole channel is built on, and syncing it without a word is
 * the point (contract section 2.1 again). A layer vanishing out from under the one
 * window actually looking at it is not that: its properties panel, its symbology, its
 * attribute table -- maybe an open edit session -- would otherwise go on pointing at
 * something proven not to exist, silently failing whatever they next tried to do. So
 * this is the one case with a caller-visible consequence, `onActiveLayerDeleted`, kept
 * to exactly that one case for the same reason the rest stays silent.
 */
export function useLiveDataState(projectId: string, options: LiveDataStateOptions): LiveDataState {
  const queryClient = useQueryClient()
  // Read fresh at the moment the debounced refresh actually runs, not closed over at
  // the moment it was scheduled -- the active layer, or the callback itself, may well
  // have changed in the settle window between the two (see `refresh`'s own re-check).
  const optionsRef = useRef(options)
  optionsRef.current = options

  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // A refresh that arrived while `workAtRisk` was true and was held back for it. Not a
  // queue -- one held refresh already means "the catalog moved since this session was
  // last known to be current", and a second one before the first runs says nothing more.
  const owed = useRef(false)

  useEffect(() => {
    // Leaving the project must not leave a timer that refetches a query nobody is
    // watching any more, or calls back into a route that has already unmounted.
    return () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current)
      timerRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Runs the refresh a burst left held back, on the render where there is nothing left
  // to lose by it -- the same shape as `useDeferredLayerJump`'s own catch-up effect, and
  // for the same reason: `owed` is a ref, so setting it renders nothing, and nothing
  // else here is watching for the moment `workAtRisk` clears.
  useEffect(() => {
    if (options.workAtRisk) return
    if (!owed.current) return
    owed.current = false
    void refresh(queryClient, projectId, optionsRef)
  }, [options.workAtRisk, queryClient, projectId])

  function notify() {
    // Collects a burst into one refetch: every further call within the window pushes
    // the deadline back out, so a run of them ends in a single request once the burst
    // itself has actually stopped.
    if (timerRef.current !== null) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      timerRef.current = null
      if (optionsRef.current.workAtRisk) {
        owed.current = true
        return
      }
      void refresh(queryClient, projectId, optionsRef)
    }, DATA_STATE_SETTLE_MS)
  }

  return { notify }
}

async function refresh(
  queryClient: QueryClient,
  projectId: string,
  optionsRef: { current: LiveDataStateOptions },
): Promise<void> {
  const targetLayerId = optionsRef.current.activeLayerId
  // Read before the fetch below overwrites the cache -- once the layer is confirmed
  // gone, this is the only place its name can still be read from.
  const before = targetLayerId
    ? queryClient
        .getQueryData<LayerSummary[]>(layerKeys.list(projectId))
        ?.find((layer) => layer.id === targetLayerId)
    : undefined

  try {
    const layers = await queryClient.fetchQuery({ ...layerListQuery(projectId), staleTime: 0 })
    if (!targetLayerId || !before) return
    // Checked again on arrival, not only when the request was sent: the user may have
    // left this layer themselves while it was in flight, and closing whatever they have
    // open *now* because a layer they left behind turned out to be gone would be wrong.
    if (optionsRef.current.activeLayerId !== targetLayerId) return
    const stillExists = layers.some((layer) => layer.id === targetLayerId)
    if (!stillExists) optionsRef.current.onActiveLayerDeleted(before)
  } catch {
    // A refetch that fails is not worth interrupting the user over -- same reasoning as
    // `useLiveViewState.ts`'s own read. The next data-state event, or the next
    // reconnect, asks again.
  }
}
