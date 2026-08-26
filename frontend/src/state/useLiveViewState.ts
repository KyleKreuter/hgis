import { useEffect, useRef } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import {
  CLIENT_ID,
  connectLiveChannel,
  isForThisProject,
  shouldFollowRemoteViewport,
  shouldReadBack,
} from '@/api/events'
import { viewStateQuery } from '@/api/projects'
import { applyRemoteSelection, useSelection } from '@/state/selection'
import { activeLayerJumpTarget, layerStateOf } from './viewState'

/**
 * How long the view waits before following a changed active layer.
 *
 * An agent working through three layers in a second would otherwise throw the view about
 * three times. The selection is not delayed by this -- that one is small, and being
 * immediate is the whole promise. A jump is the opposite: it replaces everything on
 * screen, so it is worth arriving once, at the layer the state finally settles on.
 *
 * Long enough to collect a burst, short enough that a single change still feels like an
 * answer rather than a delay.
 */
export const JUMP_SETTLE_MS = 300

export interface LiveViewStateOptions {
  /** Whether this session holds a working-state write the server has not seen yet. */
  hasPendingWrite: () => boolean
  /**
   * The saved active layer as the page loaded it. Seeds the comparison in
   * {@link activeLayerJumpTarget} -- without it the first event would look like a change
   * and move a view nobody asked to move.
   */
  loadedActiveLayerId: string | null
  /** Whether {@link loadedActiveLayerId} has been read at all yet. */
  ready: boolean
  /**
   * Someone else moved the project's active layer, and this client is not there. Called
   * at most once per burst, with the layer the state settled on -- whether the view
   * actually moves is the caller's decision (see `useDeferredLayerJump`).
   */
  onActiveLayerMoved: (layerId: string) => void
  /**
   * This project's data changed -- a layer's data, style, clip or render version, or
   * the catalog itself. Passed through with nothing but the fact that it happened: what
   * changed and what to do about it are not this hook's business (its job is the
   * working state, not the data), only that the moment happened -- see
   * `state/useLiveDataState.ts` for what actually reacts to it. Wired onto the same
   * connection this hook already owns, not a second one: the stream is one resource
   * per open project, and the two kinds of state sharing it is exactly what a "second
   * listener" (contract section 2.1) means.
   *
   * Filtered by project only (`isForThisProject`), *not* by origin the way the
   * working-state event below is: a data-state refetch never writes anything back, so
   * there is no echo loop here to break by skipping this client's own writes -- and for
   * a background job such as an import, this client's own echo is the only way it ever
   * learns the job finished. See `isForThisProject`'s own comment in `api/events.ts`.
   */
  onProjectDataState?: () => void
  /**
   * Someone else changed this project's own map viewport -- `center`/`zoom` via `PATCH
   * /api/projects/{id}` (`set_view` over MCP, or another tab dragging its map) -- and
   * this client is not the one who wrote it. Called with nothing but the fact that it
   * happened, the same shape {@link LiveViewStateOptions.onProjectDataState} uses: what
   * to read and how to move the map are not this hook's business. See
   * `map/RemoteViewport.tsx` for what actually reacts to it.
   *
   * Filtered by project and by origin, unlike `onProjectDataState`: `RemoteViewport`'s
   * `easeTo` feeds back into this client's own `ViewportPersistence` save, so an
   * unfiltered echo would have this client answer its own change with a refetch and a
   * re-save of the value it just wrote -- the same loop `shouldReadBack` breaks for the
   * working-state event below (`shouldFollowRemoteViewport` is that same rule, named for
   * this event).
   */
  onProjectViewportChanged?: () => void
}

/**
 * Keeps this project's working state in step with whoever else is changing it.
 *
 * The channel reports that a project moved and nothing more (`api/events.ts`), so the
 * whole of the work is here: read the state through the ordinary query, and put what it
 * says on screen. Reading it through the query cache rather than into a store of this
 * hook's own is what keeps one answer for the working state -- a second copy alongside it
 * would have to be told about every change the first one hears.
 *
 * <p>Two things follow from a change. The open layer's selection is applied at once. And
 * if the saved *active layer* has moved, {@link LiveViewStateOptions.onActiveLayerMoved}
 * is called so the view can follow -- the address no longer blocks that, it has to come
 * along (see `activeLayerJumpTarget` for the four cases that are not a move at all).
 */
export function useLiveViewState(
  projectId: string,
  layerId: string | null,
  options: LiveViewStateOptions,
) {
  const queryClient = useQueryClient()
  // The connection must survive a layer switch, so the effect below cannot depend on
  // `layerId` -- it reads whichever layer is open at the moment an event arrives.
  const layerIdRef = useRef(layerId)
  layerIdRef.current = layerId
  // Same reason, and the same shape as `useViewState`'s own `saveRef`: the effect is
  // created once, and must ask the current values rather than the ones it closed over.
  const optionsRef = useRef(options)
  optionsRef.current = options

  // The saved active layer as this client last knew it. `undefined` until the page's own
  // document has been read; see `activeLayerJumpTarget`.
  const knownActiveLayer = useRef<string | null | undefined>(undefined)
  useEffect(() => {
    if (options.ready && knownActiveLayer.current === undefined) {
      knownActiveLayer.current = options.loadedActiveLayerId
    }
  }, [options.ready, options.loadedActiveLayerId])

  useEffect(() => {
    const { read, cancelPendingJump } = readBackOnce(
      queryClient, projectId, layerIdRef, optionsRef, knownActiveLayer)

    const close = connectLiveChannel({
      // A reconnect means the stream was down for a while and nothing is replayed. One
      // read here is what closes that gap -- and it is why the server's own timeout on a
      // stream costs nothing: the client comes back and asks.
      onOpen: (reconnected) => {
        if (reconnected) {
          read()
          // The gap that makes a missed *working*-state event harmless (the next one
          // repeats it) makes a missed *data*-state event the opposite: nothing later
          // repeats "the catalog changed" once the moment that would have said so has
          // passed. So this is unconditional, the same as the read above, rather than
          // waiting for an event this client happened to still be connected to see.
          //
          // The price is a refetch with no event behind it, and `stream-timeout` (5m)
          // makes that a regular occurrence rather than an exception. Anyone counting
          // network requests against events will therefore find one too many and go
          // looking for a bug in the debounce -- we did, and measured our way back out:
          // provoking a reconnect with an 8s timeout and writing nothing at all still
          // produced two `GET .../layers`, twelve seconds apart. The rule is that a
          // client makes at most one request per (burst of events + reconnect), not per
          // burst alone.
          optionsRef.current.onProjectDataState?.()
          // Same reasoning as the data-state refetch just above: nothing later repeats
          // "the viewport changed" once the gap that would have carried it has passed,
          // so this too is unconditional rather than waiting for an event the reconnect
          // itself may have missed.
          optionsRef.current.onProjectViewportChanged?.()
        }
      },
      onProjectViewState: (event) => {
        // An event this client's own write produced is not a change it has to answer --
        // it already holds this state. Without this the view would jump on every layer
        // the user themselves opens.
        if (!shouldReadBack(event, { projectId, clientId: CLIENT_ID })) return
        read()
      },
      onProjectDataState: (event) => {
        if (!isForThisProject(event, projectId)) return
        optionsRef.current.onProjectDataState?.()
      },
      onProjectViewport: (event) => {
        if (!shouldFollowRemoteViewport(event, { projectId, clientId: CLIENT_ID })) return
        optionsRef.current.onProjectViewportChanged?.()
      },
    })

    return () => {
      close()
      cancelPendingJump()
    }
  }, [projectId, queryClient])
}

/**
 * A read-back that never runs twice at once, and never ends on a stale answer.
 *
 * Two events arriving within one round trip would otherwise both be served by the same
 * in-flight request -- the query cache is right to answer a second identical read from
 * the first one -- and the second event's state would never be fetched at all. So a
 * request that arrives while one is running is remembered and run afterwards. The cost is
 * one extra read at the end of a burst; the alternative is a map that quietly stays one
 * change behind.
 */
function readBackOnce(
  queryClient: QueryClient,
  projectId: string,
  layerIdRef: { current: string | null },
  optionsRef: { current: LiveViewStateOptions },
  knownActiveLayer: { current: string | null | undefined },
): { read: () => void; cancelPendingJump: () => void } {
  let running = false
  let requestedAgain = false
  let settleTimer: ReturnType<typeof setTimeout> | null = null
  let settleTarget: string | null = null

  /**
   * Collects a burst into one move. Every further change within the window replaces the
   * target and restarts the wait, so a run of them ends in a single jump to wherever the
   * state came to rest.
   */
  function scheduleJump(target: string) {
    settleTarget = target
    armJumpTimer()
  }

  function armJumpTimer() {
    if (settleTimer !== null) clearTimeout(settleTimer)
    settleTimer = setTimeout(() => {
      settleTimer = null
      // A read that is still on its way may well change where the state stands. Firing
      // now would move the view to the newest layer this client *knows about*, which is
      // not the same thing -- and for the length of that request the view would sit on a
      // layer the state no longer names, which is the very fault this whole path exists
      // to prevent. Waiting another window instead of firing: by then the read has landed
      // and either replaced the target or left it standing, and the decision is made on
      // what is true rather than on what happened to be true 300 ms ago.
      if (running) {
        armJumpTimer()
        return
      }
      const layerId = settleTarget
      settleTarget = null
      // Checked again on arrival, not only when the timer was set: the user may have
      // opened that layer themselves in the meantime, and moving them to where they
      // already are is a flicker for nothing.
      if (layerId !== null && layerId !== layerIdRef.current) {
        optionsRef.current.onActiveLayerMoved(layerId)
      }
    }, JUMP_SETTLE_MS)
  }

  async function run() {
    if (running) {
      requestedAgain = true
      return
    }
    running = true
    try {
      do {
        requestedAgain = false
        const document = await queryClient.fetchQuery({ ...viewStateQuery(projectId), staleTime: 0 })
        const layerId = layerIdRef.current
        // The read happens either way -- it costs one request and keeps the cache current
        // for the next layer that is opened. Applying it is what must not happen while
        // this session holds a write the server has not seen: what came back is then the
        // older state, and putting it on the map would take away what the user has just
        // done, only to be replaced again a moment later. Their own write is what the
        // server ends up holding, so nothing is lost by leaving the screen as it is.
        if (optionsRef.current.hasPendingWrite()) continue

        const jumpTo = activeLayerJumpTarget({
          known: knownActiveLayer.current,
          stored: document.activeLayerId,
          // Where the view will be, not where it is: a jump already waiting out the
          // settle window is as good as arrived. Comparing against the open layer instead
          // is wrong in exactly the case the window exists for -- a run of changes ending
          // back on the layer that is open. The last change then looks like "already
          // there", nothing replaces the waiting jump, and the view moves away to a layer
          // the state no longer names. With the pending target here, the last change
          // replaces it, and the arrival check below turns it into staying put.
          open: settleTarget ?? layerId,
        })
        // Recorded whether or not the view follows. This is what this client now knows
        // the saved state to be, and the next comparison has to be made against it --
        // otherwise a jump that was refused would be offered again on every later event.
        if (knownActiveLayer.current !== undefined) {
          knownActiveLayer.current = document.activeLayerId
        }
        if (jumpTo) scheduleJump(jumpTo)

        if (layerId) {
          // Not the user's own doing, so it must not be saved straight back out --
          // see `applyRemoteSelection`. Applied as it stands, without checking that the
          // fids still exist: whoever set them chose them a moment ago, a fid that is
          // gone simply highlights nothing, and the point of this path is that it is
          // immediate. The layer being jumped to gets its own selection from the
          // workspace's ordinary restore once it is open.
          const selection = layerStateOf(document, layerId).selection
          applyRemoteSelection(() => useSelection.getState().select(layerId, selection, 'replace'))
        }
      } while (requestedAgain)
    } catch {
      // A read that fails is not worth interrupting the user over: the next event, or the
      // next reconnect, asks again. Failing loudly here would put a toast on the screen
      // for every hiccup of a connection that is meant to be unnoticeable.
    } finally {
      running = false
    }
  }

  return {
    read: () => void run(),
    // Leaving the project must not leave a timer that moves a view which is no longer there.
    cancelPendingJump: () => {
      if (settleTimer !== null) clearTimeout(settleTimer)
      settleTimer = null
      settleTarget = null
    },
  }
}
