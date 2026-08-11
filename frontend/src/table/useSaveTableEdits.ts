import { useCallback } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { useApplyEdits, type EditRequest } from '@/api/edits'
import { buildUpdates } from './editBatch'
import { useTableEditing } from './useTableEditing'

/**
 * Sends the table's edit buffer as one batch and clears it on success.
 *
 * Mirrors `useEditSession.save` (plan section D.3/D.8): same endpoint, same
 * all-or-nothing batch, same error handling for a stale `rowVersion`. The table never
 * sends `creates` or `deletes` -- editing existing attributes is all it does.
 */
export function useSaveTableEdits(layerId: string, projectId: string) {
  const applyEdits = useApplyEdits(layerId, projectId)
  const reset = useTableEditing((state) => state.reset)

  const save = useCallback(async () => {
    const { edits, rowVersions } = useTableEditing.getState()
    const updates = buildUpdates(edits, rowVersions)
    if (updates.length === 0) return

    const request: EditRequest = { updates }

    try {
      const result = await applyEdits.mutateAsync(request)
      reset()
      toast.success(`${result.updated} geändert`)
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        toast.error(error.message, {
          description: 'Die Ansicht neu laden, um den aktuellen Stand zu sehen.',
        })
        return
      }
      toast.error(error instanceof ApiError ? error.message : 'Speichern fehlgeschlagen')
    }
  }, [applyEdits, reset])

  return { save, isSaving: applyEdits.isPending }
}
