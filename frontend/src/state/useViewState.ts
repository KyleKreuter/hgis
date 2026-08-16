import { useCallback, useEffect, useRef } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { projectKeys, useSaveViewState, viewStateQuery } from '@/api/projects'
import {
  EMPTY_VIEW_STATE,
  withActiveLayer,
  withQuery,
  withSort,
  planSelectionWrite,
  type ViewStateDocument,
  type ViewStateQuery,
  type ViewStateSort,
} from './viewState'

/** Mirrors `useStyleEditor`'s `DEFER_MS`: long enough that a burst of actions (three
 *  sort clicks, a run of keystrokes) collapses into one request, short enough that
 *  closing the tab a moment later still lets the flush-on-unmount below catch it. */
const DEFER_MS = 400

export interface ViewStateWriter {
  /** The latest known document: the server's answer with every not-yet-flushed write of
   *  this session already folded in, so a restore right after a write sees its own
   *  change instead of racing the deferred request. */
  document: ViewStateDocument
  /** Whether the document has loaded at least once. Restoring before this is true would
   *  mistake "not loaded yet" for "nothing was ever saved". */
  ready: boolean
  writeActiveLayer: (layerId: string | null) => void
  writeSort: (layerId: string, sort: ViewStateSort | null) => void
  writeQuery: (layerId: string, query: ViewStateQuery | null) => void
  /** Returns `false` and writes nothing when `selection` is over CONTRACT.md's "Grenze" --
   *  the caller decides how to tell the user, this only refuses to send it. */
  writeSelection: (layerId: string, selection: readonly number[]) => boolean
  /** Whether a write of this session has not reached the server yet -- either still
   *  waiting out `DEFER_MS`, or sent and not yet answered. While that is true, `document`
   *  is newer than what the server would answer with, so anything reading the server back
   *  (`state/useLiveViewState.ts`) must not apply what it finds. Deliberately a function:
   *  it is called from callbacks outside the render cycle, which need the value as it is
   *  at that moment, not as it was when they were created. */
  hasPendingWrite: () => boolean
}

/**
 * The write path for a project's working state (CONTRACT.md phase 17, schema B).
 *
 * One instance per open project, held by the workspace route (`routes/projects.$projectId.tsx`)
 * so its flush-on-unmount fires exactly when the project closes -- not on every layer
 * switch, which leaves `AttributeTable` mounted throughout. Every `write*` call is a
 * direct response to something the user just did (switched layers, sorted, searched,
 * selected); nothing here is driven by watching state and writing whatever it currently
 * holds, which is what would write empty values right as a view unmounts (see
 * CONTRACT.md's "Die wichtigste Regel").
 *
 * Each write lands in the query cache immediately (so `document` always reflects the
 * latest action) and reaches the server after `DEFER_MS`, the same shape as
 * `useStyleEditor.apply`.
 */
export function useViewStateWriter(projectId: string): ViewStateWriter {
  const queryClient = useQueryClient()
  const query = useQuery(viewStateQuery(projectId))
  const save = useSaveViewState(projectId)

  const pending = useRef<ViewStateDocument | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  // The unmount effect must not re-run whenever the mutation object changes identity.
  const saveRef = useRef(save)
  saveRef.current = save

  const flush = useCallback(() => {
    if (timer.current !== null) {
      clearTimeout(timer.current)
      timer.current = null
    }
    if (pending.current) {
      saveRef.current.mutate(pending.current, {
        onError: () => toast.error('Das Programm konnte den Arbeitsstand nicht speichern'),
      })
      pending.current = null
    }
  }, [])

  // Leaving the project while a deferred write is still waiting must not drop it.
  useEffect(() => flush, [flush])

  const commit = useCallback(
    (next: ViewStateDocument) => {
      queryClient.setQueryData(projectKeys.viewState(projectId), next)
      pending.current = next
      if (timer.current !== null) clearTimeout(timer.current)
      timer.current = setTimeout(flush, DEFER_MS)
    },
    [flush, projectId, queryClient],
  )

  // The base every write patches onto: the last pending/flushed document, not this
  // render's `query.data` -- a second write within `DEFER_MS` of the first has to build
  // on the first one's result, not discard it.
  function currentDocument(): ViewStateDocument {
    return pending.current ?? query.data ?? EMPTY_VIEW_STATE
  }

  // Plain functions, not `useCallback`: nothing downstream puts these in a dependency
  // array (`AttributeTable`'s own effects either skip `viewState` entirely or read it
  // through a ref, see `viewStateRef`), so memoising them would only add a dependency
  // list to keep in sync for no behavioural gain.
  function writeActiveLayer(layerId: string | null) {
    commit(withActiveLayer(currentDocument(), layerId))
  }
  function writeSort(layerId: string, sort: ViewStateSort | null) {
    commit(withSort(currentDocument(), layerId, sort))
  }
  function writeQuery(layerId: string, next: ViewStateQuery | null) {
    commit(withQuery(currentDocument(), layerId, next))
  }
  function writeSelection(layerId: string, selection: readonly number[]) {
    const plan = planSelectionWrite(currentDocument(), layerId, selection)
    if (!plan.document) return false
    commit(plan.document)
    return true
  }

  return {
    document: currentDocument(),
    ready: query.isSuccess,
    writeActiveLayer,
    writeSort,
    writeQuery,
    writeSelection,
    // `pending` covers the wait before the request goes out, `isPending` the wait for the
    // answer. Only both together describe the whole span in which the server does not yet
    // hold what the user has already done.
    hasPendingWrite: () => pending.current !== null || saveRef.current.isPending,
  }
}
