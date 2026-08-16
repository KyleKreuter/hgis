import { useState } from 'react'
import { changeCount } from './tableEditSession'
import { useTableEditing } from './useTableEditing'
import { useSaveTableEdits } from './useSaveTableEdits'

export interface TableEditToolbarState {
  active: boolean
  /** Number of buffered cell edits, formatted labels are the callers' job. */
  pending: number
  isSaving: boolean
  save: () => Promise<void>
  reset: () => void
  /** Whether the "leave with unsaved changes" confirmation is open. */
  confirmLeave: boolean
  /** The X: ends the session directly if nothing is pending, otherwise opens the confirm dialog. */
  requestLeave: () => void
  cancelLeave: () => void
  confirmDiscardAndLeave: () => void
}

/**
 * Shared state and actions behind the table's edit toolbar -- split out so
 * `TableEditToolbar` (the scrollable session controls) and `TableEditToolbarExit`
 * (the leave button, kept structurally outside that scroller, see there) can share one
 * `useSaveTableEdits` mutation instead of each creating their own. Two independent
 * mutations would disagree about `isSaving`: whichever piece did not trigger the save
 * would never see it in flight, and could still let the user click "leave" mid-save.
 */
export function useTableEditToolbar(layerId: string, projectId: string): TableEditToolbarState {
  const active = useTableEditing((state) => state.active)
  const pending = useTableEditing((state) => changeCount(state))
  const { save, isSaving } = useSaveTableEdits(layerId, projectId)
  const [confirmLeave, setConfirmLeave] = useState(false)

  function requestLeave() {
    if (pending > 0) {
      setConfirmLeave(true)
      return
    }
    useTableEditing.getState().end()
  }

  return {
    active,
    pending,
    isSaving,
    save,
    reset: () => useTableEditing.getState().reset(),
    confirmLeave,
    requestLeave,
    cancelLeave: () => setConfirmLeave(false),
    confirmDiscardAndLeave: () => {
      useTableEditing.getState().end()
      setConfirmLeave(false)
    },
  }
}
