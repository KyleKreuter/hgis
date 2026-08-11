import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { useApplyEdits, type EditRequest } from '@/api/edits'
import { countChanges, useEditing } from '@/state/editing'
import { endMeasurement } from '@/measurement/store'
import type { DrawTool } from './DrawController'
import type { SnapTarget } from './snapping'

interface EditSessionOptions {
  layerId: string | null
  projectId: string
}

/**
 * The editing session: turning the mode on and off, saving, discarding, and the
 * guardrails around unsaved work.
 *
 * Lives apart from the components because all three of them -- toolbar, draw controller
 * and attribute form -- need parts of it, and threading it through props would make the
 * workspace own state it has no interest in.
 */
export function useEditSession({ layerId, projectId }: EditSessionOptions) {
  const [active, setActive] = useState(false)
  const [tool, setTool] = useState<DrawTool>('select')
  const [selectedFid, setSelectedFid] = useState<number | null>(null)
  /** Set when the server refused a geometry; drives the explicit repair prompt. */
  const [invalidGeometry, setInvalidGeometry] = useState<string | null>(null)
  /**
   * Bumped after every save. The drawing tool still holds the drafts it just sent -- with
   * placeholder ids and row versions that the write has already superseded -- so it has
   * to start over from what is now on the server.
   */
  const [reloadNonce, setReloadNonce] = useState(0)
  /** Snapping is on by default -- drawing without it is the exception, not the rule. */
  const [snapEnabled, setSnapEnabled] = useState(true)
  const [snapTarget, setSnapTarget] = useState<SnapTarget | null>(null)
  const [snapUnavailableReason, setSnapUnavailableReason] = useState<string | null>(null)
  /**
   * Other layers to snap against.
   *
   * Session state, not project state: which layers one snaps to while drawing is a
   * working preference and changes from task to task, unlike visibility and order, which
   * describe the project itself and therefore live on the server.
   */
  const [snapSourceLayerIds, setSnapSourceLayerIds] = useState<string[]>([])

  const buffer = useEditing((state) => state.buffer)
  const beginEditing = useEditing((state) => state.begin)
  const endEditing = useEditing((state) => state.end)
  const resetBuffer = useEditing((state) => state.reset)
  const applyEdits = useApplyEdits(layerId ?? '', projectId)

  const pending = countChanges(buffer)

  const start = useCallback(() => {
    if (!layerId) return
    // Before anything else, and synchronously: measuring and drawing fight over the
    // same click and over the same map handlers, so the measurement has to be gone
    // before the drawing tool is mounted -- not one effect pass later.
    endMeasurement()
    beginEditing(layerId)
    setActive(true)
    setTool('select')
  }, [layerId, beginEditing])

  const stop = useCallback(() => {
    endEditing()
    setActive(false)
    setSelectedFid(null)
    setInvalidGeometry(null)
    setSnapTarget(null)
    setSnapUnavailableReason(null)
    setSnapSourceLayerIds([])
  }, [endEditing])

  const save = useCallback(
    async (repairInvalid = false) => {
      const current = useEditing.getState().buffer
      if (countChanges(current) === 0) return

      const request: EditRequest = {
        creates: Object.values(current.creates).map((feature) => ({
          clientId: feature.fid,
          geometry: feature.geometry,
          properties: feature.properties,
        })),
        updates: Object.values(current.updates).map((feature) => ({
          fid: feature.fid,
          // Sent so the server can tell whether anyone else wrote the row meanwhile.
          rowVersion: feature.rowVersion,
          geometry: feature.geometry,
          properties: feature.properties,
        })),
        deletes: current.deletes,
        repairInvalid,
      }

      try {
        const result = await applyEdits.mutateAsync(request)
        // Order matters (plan section D.3): clear the buffer first, which drops the tile
        // filter, and only then let the refreshed dataVersion pull new tiles. The other
        // way round the old geometry flashes back for a frame.
        resetBuffer()
        setSelectedFid(null)
        setInvalidGeometry(null)
        setReloadNonce((previous) => previous + 1)

        const created = Object.keys(result.createdFids).length
        toast.success(
          [
            created > 0 && `${created} angelegt`,
            result.updated > 0 && `${result.updated} geändert`,
            result.deleted > 0 && `${result.deleted} gelöscht`,
          ]
            .filter(Boolean)
            .join(', ') || 'Gespeichert',
        )
      } catch (error) {
        if (error instanceof ApiError && error.message.includes('Ungültige Geometrie')) {
          // Never repaired automatically: ST_MakeValid changes the shape, and whether
          // that is acceptable is the user's decision, not ours.
          setInvalidGeometry(error.message)
          return
        }
        if (error instanceof ApiError && error.status === 409) {
          toast.error(error.message, {
            description: 'Die Ansicht neu laden, um den aktuellen Stand zu sehen.',
          })
          return
        }
        toast.error(error instanceof ApiError ? error.message : 'Speichern fehlgeschlagen')
      }
    },
    [applyEdits, resetBuffer],
  )

  const discard = useCallback(() => {
    resetBuffer()
    setSelectedFid(null)
    setInvalidGeometry(null)
  }, [resetBuffer])

  /**
   * Deletes the selected feature through the buffer. The drawing surface follows via
   * `historyNonce` -- same channel undo uses -- so the toolbar does not need a handle on
   * terra-draw. Disabled in the UI when nothing is selected.
   */
  const deleteSelected = useCallback(() => {
    if (selectedFid === null) return
    useEditing.getState().removeFeature(selectedFid)
    setSelectedFid(null)
  }, [selectedFid])

  // Closing the tab with unsaved edits. The browser shows its own wording; all we can do
  // is ask for the prompt at all (plan section D.8).
  useEffect(() => {
    if (pending === 0) return

    function warn(event: BeforeUnloadEvent) {
      event.preventDefault()
      event.returnValue = ''
    }

    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [pending])

  const selectedFeature =
    selectedFid === null
      ? null
      : (buffer.creates[selectedFid] ?? buffer.updates[selectedFid] ?? null)

  return {
    active,
    reloadNonce,
    snapEnabled,
    toggleSnap: () => setSnapEnabled((previous) => !previous),
    snapTarget,
    setSnapTarget,
    snapUnavailableReason,
    setSnapUnavailableReason,
    snapSourceLayerIds,
    toggleSnapSource: (id: string) =>
      setSnapSourceLayerIds((previous) =>
        previous.includes(id) ? previous.filter((entry) => entry !== id) : [...previous, id],
      ),
    tool,
    setTool,
    selectedFid,
    setSelectedFid,
    selectedFeature,
    deleteSelected,
    pending,
    invalidGeometry,
    dismissInvalidGeometry: () => setInvalidGeometry(null),
    start,
    stop,
    save,
    discard,
    isSaving: applyEdits.isPending,
  }
}
