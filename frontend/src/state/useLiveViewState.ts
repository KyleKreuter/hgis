import { useEffect, useRef } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { CLIENT_ID, connectLiveChannel, shouldReadBack } from '@/api/events'
import { viewStateQuery } from '@/api/projects'
import { applyRemoteSelection, useSelection } from '@/state/selection'
import { layerStateOf } from './viewState'

/**
 * Keeps this project's working state in step with whoever else is changing it.
 *
 * The channel reports that a project moved and nothing more (`api/events.ts`), so the
 * whole of the work is here: read the state through the ordinary query, and put what it
 * says about the open layer on the map. Reading it through the query cache rather than
 * into a store of this hook's own is what keeps one answer for the working state -- a
 * second copy alongside it would have to be told about every change the first one hears.
 *
 * <p>What is applied is the open layer's selection. A fid means nothing outside its layer,
 * and which layer is open belongs to the address (see the workspace route), so a change
 * that concerns another layer is read into the cache but shows nothing until that layer is
 * opened -- at which point the ordinary restore picks it up from the same document.
 */
export function useLiveViewState(
  projectId: string,
  layerId: string | null,
  hasPendingWrite: () => boolean,
) {
  const queryClient = useQueryClient()
  // The connection must survive a layer switch, so the effect below cannot depend on
  // `layerId` -- it reads whichever layer is open at the moment an event arrives.
  const layerIdRef = useRef(layerId)
  layerIdRef.current = layerId
  // Same reason, and the same shape as `useViewState`'s own `saveRef`: the effect is
  // created once, and must ask the current writer rather than the one it closed over.
  const hasPendingWriteRef = useRef(hasPendingWrite)
  hasPendingWriteRef.current = hasPendingWrite

  useEffect(() => {
    const read = readBackOnce(queryClient, projectId, layerIdRef, hasPendingWriteRef)

    return connectLiveChannel({
      // A reconnect means the stream was down for a while and nothing is replayed. One
      // read here is what closes that gap -- and it is why the server's own timeout on a
      // stream costs nothing: the client comes back and asks.
      onOpen: (reconnected) => {
        if (reconnected) read()
      },
      onProjectViewState: (event) => {
        if (!shouldReadBack(event, { projectId, clientId: CLIENT_ID })) return
        read()
      },
    })
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
  hasPendingWriteRef: { current: () => boolean },
): () => void {
  let running = false
  let requestedAgain = false

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
        if (layerId && !hasPendingWriteRef.current()) {
          // Not the user's own doing, so it must not be saved straight back out --
          // see `applyRemoteSelection`. Applied as it stands, without checking that the
          // fids still exist: whoever set them chose them a moment ago, a fid that is
          // gone simply highlights nothing, and the point of this path is that it is
          // immediate.
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

  return () => void run()
}
