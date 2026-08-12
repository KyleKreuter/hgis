import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { useApplyEdits, type EditRequest } from '@/api/edits'
import { countChanges, useEditing, type DraftFeature } from '@/state/editing'
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
  /**
   * The loaded, unedited state of the selected feature -- kept apart from the buffer on
   * purpose. Selecting an existing feature writes nothing into `buffer.updates` (a plain
   * click must not count as a change, see `dirtyFids` in state/editing.ts), so it has no
   * buffer entry to show until it is actually edited. This is what `selectedFeature`
   * falls back to in the meantime; see `resolveSelectedFeature`.
   */
  const [selectedOriginal, setSelectedOriginal] = useState<DraftFeature | null>(null)
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

  /**
   * Records the current selection, passed down as `DrawController`'s `onSelectFeature`.
   * `original` is DrawController's own loaded copy of an existing feature, or null for a
   * freshly drawn one (already in `buffer.creates`, see `resolveSelectedFeature`) and for
   * "nothing selected". Deliberately the only place either piece of state is set, so the
   * two never drift apart.
   */
  const selectFeature = useCallback((fid: number | null, original: DraftFeature | null) => {
    setSelectedFid(fid)
    setSelectedOriginal(original)
  }, [])

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
    selectFeature(null, null)
    setInvalidGeometry(null)
    setSnapTarget(null)
    setSnapUnavailableReason(null)
    setSnapSourceLayerIds([])
  }, [endEditing, selectFeature])

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
        selectFeature(null, null)
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
            description: 'Laden Sie die Ansicht neu, um den aktuellen Stand zu sehen',
          })
          return
        }
        toast.error(error instanceof ApiError ? error.message : 'Speichern fehlgeschlagen')
      }
    },
    [applyEdits, resetBuffer, selectFeature],
  )

  const discard = useCallback(() => {
    resetBuffer()
    selectFeature(null, null)
    setInvalidGeometry(null)
  }, [resetBuffer, selectFeature])

  /**
   * Deletes the selected feature through the buffer. The drawing surface follows via
   * `historyNonce` -- same channel undo uses -- so the toolbar does not need a handle on
   * terra-draw. Disabled in the UI when nothing is selected.
   */
  const deleteSelected = useCallback(() => {
    if (selectedFid === null) return
    useEditing.getState().removeFeature(selectedFid)
    selectFeature(null, null)
  }, [selectedFid, selectFeature])

  // Closing the tab, navigating away, and switching layers with unsaved edits are all
  // covered by one workspace-wide guard now -- `leaveGuard` in
  // `routes/projects.$projectId.tsx`, built on `useBlocker` -- rather than a beforeunload
  // listener owned by this hook alone. That guard watches the table's buffer too, so a
  // single mechanism asks regardless of which mode is dirty (plan section D.8).
  //
  // What that guard does *not* cover is the clean case: with an empty buffer the switch
  // goes through unasked, and the session was left pointed at the layer before it. That
  // stale `useEditing.layerId` is what `DeleteLayerDialog` and `ManageFieldsDialog` lock
  // on, so it locked the wrong layer and let a field be deleted out from under the one
  // actually being edited -- the exact thing the lock exists to prevent, since the buffer
  // is column_name-keyed. Ended here rather than in the route, which owns the same guard
  // for the table session (`useTableEditing`) and would otherwise need a second copy of
  // it: this hook already sees every change of the active layer through its own argument.
  useEffect(() => {
    if (isStaleEditSession(useEditing.getState().layerId, layerId)) stop()
  }, [layerId, stop])

  const selectedFeature = resolveSelectedFeature(buffer, selectedFid, selectedOriginal)

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
    selectFeature,
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

/**
 * Whether a running drawing session belongs to a layer that is no longer the active one.
 *
 * `sessionLayerId` is `useEditing.getState().layerId`, which is non-null exactly while a
 * drawing session is open -- the same fact `DeleteLayerDialog` and `ManageFieldsDialog`
 * lock on. `activeLayerId` is `null` when the user closed the layer altogether, which
 * leaves a session just as stranded as a switch to another one does.
 *
 * Exported as a plain function so the decision can be checked without rendering, the same
 * reasoning as `resolveSelectedFeature` below.
 */
export function isStaleEditSession(
  sessionLayerId: string | null,
  activeLayerId: string | null,
): boolean {
  return sessionLayerId !== null && sessionLayerId !== activeLayerId
}

/** The buffer shape, read off the store itself so this file need not export its own copy. */
type EditBuffer = ReturnType<typeof useEditing.getState>['buffer']

/**
 * What the attribute form should show for the current selection.
 *
 * The buffer takes precedence: once a feature has actually been edited -- geometry or
 * properties -- its buffer entry is the current truth and `selectedOriginal` is stale by
 * comparison. A feature that has only been clicked has no buffer entry at all, by design
 * (a selection must never register as a change, see `dirtyFids` in state/editing.ts), so
 * `selectedOriginal` -- the copy `DrawController` loaded it from -- is what lets it show
 * up in the form regardless.
 *
 * Exported as a plain function, apart from the hook, so this -- the part that actually
 * decides what gets shown -- can be tested without rendering a component.
 */
export function resolveSelectedFeature(
  buffer: EditBuffer,
  selectedFid: number | null,
  selectedOriginal: DraftFeature | null,
): DraftFeature | null {
  if (selectedFid === null) return null
  return buffer.creates[selectedFid] ?? buffer.updates[selectedFid] ?? selectedOriginal
}
