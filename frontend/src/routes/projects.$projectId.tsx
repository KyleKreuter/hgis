import { useCallback, useEffect, useRef, useState } from 'react'
import { createFileRoute, Link, useBlocker, useNavigate, useRouter } from '@tanstack/react-router'
import { useQuery, useQueryClient, useSuspenseQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, Globe, Image as ImageIcon, Plus, Upload } from 'lucide-react'
import { WorkspaceLayout } from '@/layout/WorkspaceLayout'
import { Separator } from '@/components/ui/separator'
import { Button, buttonVariants } from '@/components/ui/button'
import { ApiError } from '@/api/client'
import { ensureProjectLoaded, projectDetailQuery } from '@/api/projects'
import { countChanges, useEditing } from '@/state/editing'
import {
  describeUnsavedChanges,
  describeUnsavedWork,
  hasUnsavedWork,
  totalUnsavedChanges,
  unsavedChangesVerb,
} from '@/state/unsavedChanges'
import { AddMapImageDialog, GeoportalDialog } from '@/geoportal'
import { CreateLayerDialog, ImportDialog, LayerProperties, LayerTree } from '@/layers'
import { ProjectMap, type ViewportRequest, type ZoomRequest } from '@/map'
import { SymbologyPanel } from '@/styling'
import {
  AttributeTable,
  DiscardEditsDialog,
  tableChangeCount,
  useIsTableEditing,
  useTableEditing,
} from '@/table'
import {
  AttributeForm,
  DrawController,
  EditToolbar,
  EditingTileFilter,
  InvalidGeometryDialog,
  SnapMarker,
  StructureOverlay,
  StructureToolbar,
  useEditSession,
  useIsDrawingSplitLine,
} from '@/editing'
import { MeasurementOverlay, MeasurementToolbar, useIsMeasuring } from '@/measurement'
import { isVectorLayer, layerDetailQuery, layerListQuery, type LayerSummary } from '@/api/layers'
import { featureDetailQuery } from '@/api/features'
import { applyRemoteSelection, useSelection } from '@/state/selection'
import { useDeferredLayerJump } from '@/state/useDeferredLayerJump'
import { useLiveDataState } from '@/state/useLiveDataState'
import { useLiveViewState } from '@/state/useLiveViewState'
import { useViewStateWriter } from '@/state/useViewState'
import { layerJumpBackTarget, layerStateOf, shouldRestoreActiveLayer } from '@/state/viewState'
import { boundsOfGeometry } from '@/map/geometryBounds'
import { RectangleSelectTool } from '@/map/RectangleSelectTool'
import { RectangleSelectToolbar } from '@/map/RectangleSelectToolbar'
import { useIsRectangleSelecting } from '@/map/rectangleSelectStore'

interface WorkspaceSearch {
  /** Active layer. Lives in the URL so a working state survives a reload and can be shared. */
  layer?: string
}

export const Route = createFileRoute('/projects/$projectId')({
  validateSearch: (search: Record<string, unknown>): WorkspaceSearch => ({
    layer: typeof search.layer === 'string' ? search.layer : undefined,
  }),
  // Loading here (with open=true) means the workspace never mounts against empty data,
  // and last_opened_at is refreshed exactly once per visit.
  loader: ({ context, params }) => ensureProjectLoaded(context.queryClient, params.projectId),
  component: Workspace,
  errorComponent: ProjectLoadError,
})

/**
 * The workspace shell: layer tree on the left, map in the middle, attribute table below.
 *
 * The three panels are coupled through two things and nothing else -- the active layer,
 * which lives in the URL, and the selection store, which both the map and the table
 * write to. Everything else each panel loads for itself.
 */
function Workspace() {
  const { projectId } = Route.useParams()
  const { layer: activeLayerId } = Route.useSearch()
  const navigate = useNavigate({ from: Route.fullPath })
  const queryClient = useQueryClient()
  const { data: project } = useSuspenseQuery(projectDetailQuery(projectId, true))
  const { data: layers } = useQuery(layerListQuery(projectId))
  const [importOpen, setImportOpen] = useState(false)
  const [createLayerOpen, setCreateLayerOpen] = useState(false)
  const [geoportalOpen, setGeoportalOpen] = useState(false)
  const [addMapImageOpen, setAddMapImageOpen] = useState(false)
  // A counter, not a timestamp: zooming to the same layer twice has to produce a new
  // request object, and a counter does that without depending on the clock.
  const [zoomTo, setZoomTo] = useState<ZoomRequest | null>(null)
  // Same reasoning, same shape: a remote viewport change re-announces itself with a
  // fresh `nonce` even if it happens to land back on a value this client has already
  // seen (`RemoteViewport`'s own `ZoomRequest`-style request).
  const [remoteViewport, setRemoteViewport] = useState<ViewportRequest | null>(null)
  const clearSelection = useSelection((state) => state.clear)
  const viewState = useViewStateWriter(projectId)
  const editing = useEditSession({ layerId: activeLayerId ?? null, projectId })
  // Only the on/off fact, not the running measurement -- the sketch changes with every
  // mouse move, and re-rendering the whole workspace for that would be absurd.
  const measuring = useIsMeasuring()
  const rectSelecting = useIsRectangleSelecting()
  // Same reason as the two above: the split line is being drawn on the map, so every
  // other tool that wants the same click has to stand down while it is.
  const splittingLine = useIsDrawingSplitLine()
  const tableActive = useIsTableEditing()
  const tableChanges = useTableEditing(tableChangeCount)
  const tableLayerId = useTableEditing((state) => state.layerId)
  // Which dirty session a mode switch would discard -- null means neither is blocked.
  const [pendingSwitch, setPendingSwitch] = useState<'toTable' | 'toMap' | null>(null)
  // Set while the drawing mode is being left with unsaved changes still in the buffer.
  const [confirmLeaveMap, setConfirmLeaveMap] = useState(false)
  // Only the detail carries the field list the attribute form is generated from.
  const { data: activeLayerDetail } = useQuery({
    ...layerDetailQuery(activeLayerId ?? ''),
    enabled: Boolean(activeLayerId),
  })

  const activeLayer = layers?.find((layer) => layer.id === activeLayerId) ?? null
  // Narrowed once, here: a Kartenbild has no geometry, so editing, structural tools,
  // the rectangle select and the symbology panel all key off this rather than off
  // `activeLayer` directly (plan Stufe 4, "Bearbeiten und Rechteckauswahl dürfen für
  // ein Kartenbild nicht angeboten werden").
  const activeVectorLayer = activeLayer && isVectorLayer(activeLayer) ? activeLayer : null

  // Restores the last active layer exactly once per visit -- CONTRACT.md rule 4: the
  // address always wins, this only fills in when it says nothing (`activeLayerId` is
  // `undefined`, not merely falsy). Guarded by a ref rather than re-checking `activeLayerId`
  // in the condition: once this has run, a later `undefined` (the user explicitly closing
  // the layer via `selectLayer(null)`) must stay closed, not restore again.
  const restoredActiveLayer = useRef(false)
  useEffect(() => {
    if (restoredActiveLayer.current || !viewState.ready) return
    restoredActiveLayer.current = true
    const toRestore = shouldRestoreActiveLayer(activeLayerId, viewState.document)
    if (toRestore) {
      chosenLayerId.current = toRestore
      navigate({ search: { layer: toRestore }, replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewState.ready])

  /**
   * The layer the user last opened themselves -- the address they arrived with, or
   * whichever one they have picked since. A jump never touches it, which is what makes it
   * the honest way back out of a chain of them: after A -> B -> C, this still says A.
   */
  const chosenLayerId = useRef<string | null>(activeLayerId ?? null)

  function requestZoom(extent: [number, number, number, number]) {
    setZoomTo((previous) => ({ extent, nonce: (previous?.nonce ?? 0) + 1 }))
  }

  /**
   * Someone else moved this project's own map viewport -- `set_view` over MCP, or
   * another open tab. Rereads the project so the values driving `easeTo` are the ones
   * that were actually written, not whatever this tab still has cached from before the
   * event, then hands them to `RemoteViewport` as a fresh request.
   *
   * A project that has never been viewed carries no center/zoom at all (`ProjectDetail`
   * marks both nullable) -- nothing to follow to, so this simply does nothing rather
   * than easing to a made-up position.
   */
  async function handleRemoteViewportChanged() {
    const detail = await queryClient.fetchQuery({ ...projectDetailQuery(projectId), staleTime: 0 })
    if (!detail.center || detail.zoom == null) return
    const center = detail.center
    const zoom = detail.zoom
    setRemoteViewport((previous) => ({ center, zoom, nonce: (previous?.nonce ?? 0) + 1 }))
  }

  function selectLayer(layerId: string | null) {
    // The user has just said where they want to be, so a jump that is still waiting for
    // them to finish is no longer wanted -- carrying it out afterwards would take the
    // layer they just picked away again.
    deferredJump.cancel()
    // A fid means nothing outside its layer, so a selection cannot survive the switch.
    clearSelection()
    // The one place this is written: every call here is the user picking a layer -- from
    // the tree, from a dialog that just created one, or from the way back out of a jump.
    chosenLayerId.current = layerId
    // The switch itself is the action that makes this worth remembering (CONTRACT.md's
    // "Die wichtigste Regel") -- not an effect watching `activeLayerId`, which would fire
    // again, with a stale value, on every unrelated re-render of this route.
    viewState.writeActiveLayer(layerId)
    // This `navigate` is exactly what `leaveGuard` below intercepts: switching layers
    // with unsaved edits open asks before it goes through, the same as leaving the page.
    navigate({ search: { layer: layerId ?? undefined }, replace: true })
  }

  /**
   * Fetches the one feature's geometry and flies there. Goes through the query cache, so
   * zooming to a row that Identify already opened costs no request at all.
   */
  async function zoomToFeature(fid: number) {
    if (!activeLayerId) return
    try {
      const feature = await queryClient.fetchQuery(featureDetailQuery(activeLayerId, fid))
      const bounds = boundsOfGeometry(feature.geometry)
      if (bounds) requestZoom(bounds)
    } catch {
      toast.error('Das Programm konnte das Objekt nicht laden')
    }
  }

  // Two edit buffers on the same features are unmanageable, so only one of the map's
  // and the table's edit modes is ever on at a time (CONTRACT.md). Starting one ends a
  // dirty other one only after asking -- discarding unsaved work without a word is not
  // acceptable in either direction.

  function requestStartMapEditing() {
    if (tableActive && tableChanges > 0) {
      setPendingSwitch('toMap')
      return
    }
    if (tableActive) useTableEditing.getState().end()
    editing.start()
  }

  /**
   * The X in the drawing toolbar. Ends the session outright when nothing is pending --
   * asking about zero changes is noise -- and otherwise puts the same question every
   * other exit from a dirty buffer asks.
   */
  function requestLeaveMapEditing() {
    if (editing.pending > 0) {
      setConfirmLeaveMap(true)
      return
    }
    editing.stop()
  }

  function requestStartTableEditing() {
    if (!activeLayerId) return
    if (editing.active && editing.pending > 0) {
      setPendingSwitch('toTable')
      return
    }
    if (editing.active) editing.stop()
    useTableEditing.getState().begin(activeLayerId)
  }

  // Switching the active layer leaves a running table session pointed at fids and
  // columns that belong to a different layer. A dirty session can no longer reach this
  // effect at all: `leaveGuard` below asks before the URL -- and therefore
  // `activeLayerId` -- is allowed to change while the table buffer holds anything, and
  // confirming there already ends the session before the switch goes through. What is
  // left to handle here is the clean case, which is not a loss and may end quietly, same
  // as before.
  useEffect(() => {
    if (tableActive && tableLayerId !== null && tableLayerId !== activeLayerId) {
      useTableEditing.getState().end()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeLayerId])

  const unsavedChangesCount = totalUnsavedChanges(editing.pending, tableChanges)
  // What the user would lose, not what a buffer holds: a shape whose corners are set but
  // which is not closed yet counts too. It reaches the buffer only on `finish`, so
  // `unsavedChangesCount` is still 0 while it is being drawn -- and everything that ends
  // the drawing session throws it away.
  const sketching = useEditing((state) => state.sketching)
  const unsavedWork = { mapChanges: editing.pending, tableChanges, sketching }
  const workAtRisk = hasUnsavedWork(unsavedWork)

  // Reads both buffers fresh at call time rather than closing over `editing.pending` /
  // `tableChanges` -- `shouldBlockFn` and `enableBeforeUnload` both run outside React's
  // render cycle, and a save that just happened has to be visible immediately, not
  // whatever was current when this function was created.
  const hasPendingWork = useCallback(
    () =>
      hasUnsavedWork({
        mapChanges: countChanges(useEditing.getState().buffer),
        tableChanges: tableChangeCount(useTableEditing.getState()),
        sketching: useEditing.getState().sketching,
      }),
    [],
  )

  // Guards every way of leaving this page while work is unsaved: the "Zur Projektliste"
  // link, switching the active layer (also a navigation, see `selectLayer`), the
  // browser's back/forward buttons, and closing the tab. Deliberately blanket rather than
  // distinguishing `current`/`next` -- everything this route holds unsaved lives only
  // here, so any navigation away from it would lose the same thing. Separate from
  // `pendingSwitch` above on purpose: that one guards switching between map- and
  // table-editing *mode*, a local state change that never touches the URL, while this one
  // guards the router itself -- neither can fire for the other's trigger.
  const leaveGuard = useBlocker({
    shouldBlockFn: hasPendingWork,
    enableBeforeUnload: hasPendingWork,
    withResolver: true,
  })

  /**
   * Follows a layer switch that came from somewhere else.
   *
   * Deliberately not `selectLayer`: that one also *saves* the switch, and this switch is
   * already what the saved state says. Writing it back would answer someone else's change
   * with a change of our own -- the same reason `applyRemoteSelection` exists on the
   * selection side, and the same loop it avoids.
   *
   * `replace`, not `push`, for two reasons. A layer switch has never been a history entry
   * in this workspace, and making the remote one an entry while the user's own is not
   * would be incoherent. And an agent working through ten layers would bury "back to the
   * project list" under ten steps -- the browser's back button has to stay a page control.
   * What replaces it is the toast below, which is better at this job anyway: it says what
   * happened, which no history entry can, and its way back leads to the layer the user was
   * actually on rather than to some point in a chain.
   */
  function jumpToLayer(layerId: string) {
    // The layer the *user* last opened, not the one this client was on a moment ago.
    // Reading `activeLayerId` here was wrong as soon as two jumps followed each other:
    // the second one then offered the way back to the first jump's destination -- a place
    // the user had never chosen -- and the real starting point was gone for good.
    const cameFrom = layerJumpBackTarget(chosenLayerId.current, layerId)
    const cameFromName = cameFrom ? layerNameOf(cameFrom) : undefined
    // Not the user's own doing, so it must not be saved back out. `select` on a different
    // layer discards the previous selection by itself -- a fid means nothing outside its
    // layer. Applied here rather than left to the attribute table's restore, which only
    // runs on a layer's first visit and would leave a second jump back showing nothing.
    const selection = layerStateOf(viewState.document, layerId).selection
    applyRemoteSelection(() => useSelection.getState().select(layerId, selection, 'replace'))
    navigate({ search: { layer: layerId }, replace: true })

    toast.info(`Der Layer „${layerNameOf(layerId)}“ wurde von außen geöffnet`, {
      // One id for all of them, so a second jump replaces the first hint instead of
      // stacking beside it. Two hints offering two different ways back is a choice the
      // user should never have to make -- and only the newest one is still true.
      id: 'live-layer-jump',
      // Long enough to read the sentence and decide, rather than the default few seconds:
      // the view has just changed under the user, and the way back is the whole point.
      duration: 12_000,
      action: cameFrom
        ? { label: `Zurück zu „${cameFromName}“`, onClick: () => selectLayer(cameFrom) }
        : undefined,
    })
  }

  /** The layer's name, or a neutral stand-in while the catalog has not caught up. */
  function layerNameOf(layerId: string): string {
    return layers?.find((layer) => layer.id === layerId)?.name ?? 'ohne Namen'
  }

  /**
   * The layer this window has open no longer exists -- someone else deleted it while it
   * was open here (contract section 2.3's "Sonderfall"). Only ever called with nothing
   * at risk: `useLiveDataState`'s `workAtRisk` gate holds the whole refresh back while
   * an edit or table session on this layer has unsaved work, so by the time this runs
   * there is no buffer left for `leaveGuard` to ask about either.
   *
   * Deliberately not routed through `deferredJump`: that hook holds a jump back while
   * there is unsaved work, because its destination still exists and is worth waiting
   * for -- saving first and arriving a moment later loses nothing. Here the destination
   * is the empty workspace, not another layer, and there is no "waiting" that would
   * change what has to happen; the layer will not un-delete itself. So this closes it
   * immediately, straight through `selectLayer`, the same call the local delete path
   * uses (`LayerTree.tsx`'s `DeleteLayerDialog.onDeleted`).
   *
   * `selectLayer` *saves* the switch (`viewState.writeActiveLayer`), which is exactly
   * what `jumpToLayer` above deliberately avoids -- "answering someone else's change
   * with a change of our own". This is not that loop: `jumpToLayer` answers a
   * *working*-state event with another working-state write, which is what would repeat.
   * This answers a *data*-state event (a deletion) with a working-state write, and nothing
   * reads a working-state event back into a data-state refetch (`readBackOnce` in
   * `useLiveViewState.ts` never touches `layerListQuery`), so there is no cycle for it to
   * join. Checked through explicitly (Prüfer's three questions):
   *
   * <ul>
   * <li><b>Does it settle?</b> Two windows with the same active layer both react to the
   *     same deletion and each writes `null` once -- at most two `project-view-state`
   *     events. Each is read back by the other (`shouldReadBack`), but a `stored: null`
   *     never schedules a jump (`activeLayerJumpTarget`'s own "falls out as null on its
   *     own"), so neither read provokes a further write. Three events total, then quiet.
   * <li><b>Does it overwrite another window's choice?</b> Yes, and knowingly:
   *     `activeLayerId` is one shared field, not one per window, so writing `null` here
   *     can leave it naming nothing even while a second window is still actively looking
   *     at a *different* layer. That window is unaffected either way -- its own address
   *     wins, and `null` is never a jump target -- but a third, freshly opened tab with
   *     no `?layer=` of its own would now restore to "nothing" instead of to what that
   *     second window has open. Accepted, not new: the *local* delete of one's own
   *     active layer already overwrites the field the same way (`LayerTree.tsx`), and it
   *     has never been more than a best-effort hint for a session with no layer choice
   *     of its own yet -- this path just makes a rarer trigger for an existing trade-off.
   * <li><b>Is there a way back?</b> No: a working-state event never triggers a data-state
   *     refetch (see above), so the two event types cannot alternate. The settling in
   *     the first point is the whole story, not a temporary lull.
   * </ul>
   */
  function handleActiveLayerDeleted(layer: Pick<LayerSummary, 'id' | 'name'>) {
    toast.error(`Der Layer „${layer.name}“ wurde von außen gelöscht`, {
      id: 'live-layer-deleted',
      duration: 12_000,
    })
    selectLayer(null)
  }

  // Waits out unsaved work before moving the view; see the hook for why saving and
  // discarding are the same thing to it.
  const deferredJump = useDeferredLayerJump(workAtRisk, jumpToLayer)

  // Keeps the layer catalog (`layerListQuery`) in step with whoever else is writing to
  // this project's data -- see the hook for why reacting to that is almost nothing, and
  // for why it is held back entirely while `workAtRisk`: refreshing it out from under a
  // running edit session would unmount the drawing surface or the attribute table the
  // instant the fetch landed, taking the buffer's only visible way back with it.
  const dataState = useLiveDataState(projectId, {
    activeLayerId: activeLayerId ?? null,
    onActiveLayerDeleted: handleActiveLayerDeleted,
    workAtRisk,
  })

  // Held by this route rather than by the map or the table: the stream belongs to the open
  // project, so it opens when the project opens and closes when the project is left. A
  // layer switch leaves it alone.
  useLiveViewState(projectId, activeLayerId ?? null, {
    hasPendingWrite: viewState.hasPendingWrite,
    loadedActiveLayerId: viewState.document.activeLayerId,
    ready: viewState.ready,
    onActiveLayerMoved: deferredJump.request,
    onProjectDataState: dataState.notify,
    onProjectViewportChanged: handleRemoteViewportChanged,
  })

  return (
    <>
      <ImportDialog projectId={projectId} open={importOpen} onOpenChange={setImportOpen} />
      <CreateLayerDialog
        projectId={projectId}
        open={createLayerOpen}
        onOpenChange={setCreateLayerOpen}
        onCreated={(layerId) => selectLayer(layerId)}
      />
      <GeoportalDialog
        projectId={projectId}
        open={geoportalOpen}
        onOpenChange={setGeoportalOpen}
        onLayerAdded={(layerId) => selectLayer(layerId)}
      />
      <AddMapImageDialog
        projectId={projectId}
        open={addMapImageOpen}
        onOpenChange={setAddMapImageOpen}
        onCreated={(layerId) => selectLayer(layerId)}
      />
      <InvalidGeometryDialog
        message={editing.invalidGeometry}
        onRepair={() => void editing.save(true)}
        onCancel={editing.dismissInvalidGeometry}
      />
      <WorkspaceLayout
        toolbar={
          <>
            {/* Styled as a button but rendered as a real anchor: it navigates, so it
                must keep link semantics (middle click, open in new tab, screen readers). */}
            <Link
              to="/"
              className={buttonVariants({ variant: 'ghost', size: 'icon-sm' })}
              aria-label="Zur Projektliste"
            >
              <ArrowLeft className="size-3.5" />
            </Link>
            {/* Truncates instead of wrapping its own text: a long name broke onto three
                lines in a narrow window and pushed itself out of the toolbar's fixed
                height, over the map below. The title attribute keeps the full name
                reachable, and max-w-80 keeps a very long one from taking the whole line.

                Nothing here holds it open, and nothing needs to: it used to be squeezed
                to nothing by the buttons long before they ran out of room themselves,
                which is what left a project called "Test" as an empty gap. Now the header
                wraps rather than press on its contents (see `WorkspaceLayout`), so the
                name only ever gives way when a line of its own is too narrow for it. */}
            <span className="max-w-80 truncate font-medium" title={project.name}>
              {project.name}
            </span>
            {/* The variant must match the primitive's data-vertical:self-stretch --
                tailwind-merge treats prefixed and unprefixed utilities as separate
                groups, so a bare self-center would not replace it. */}
            <Separator orientation="vertical" className="h-4 data-vertical:self-center" />
            <span className="text-xs text-muted-foreground">EPSG:{project.srid}</span>

            {/* Wraps within itself once a whole line is no longer enough for it, so the
                header grows by a line instead of pushing its last buttons off the edge --
                see the header in `WorkspaceLayout`. justify-end keeps every line of it
                against the right edge, which is where ml-auto puts the first one. */}
            <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
              {/* Measuring answers a question about the map and writes nothing, so it
                  stands before the editing tools rather than inside them. */}
              <MeasurementToolbar disabled={editing.active || splittingLine} />
              <Separator orientation="vertical" className="h-4 data-vertical:self-center" />
              <RectangleSelectToolbar
                disabled={editing.active || splittingLine}
                canUse={Boolean(activeVectorLayer)}
                clipVersion={activeLayer?.clipVersion}
              />
              <Separator orientation="vertical" className="h-4 data-vertical:self-center" />
              {/* Structural editing works on the saved state and on the ordinary
                  selection, so it stands next to the drawing tools rather than inside
                  them -- and locks itself while either buffer holds anything. Brings its
                  own trailing separator: on a point layer -- or a Kartenbild, which
                  reports no geometryType at all -- it renders nothing, and a separator
                  left behind here would leave two of them side by side. */}
              <StructureToolbar
                layerId={activeLayerId ?? null}
                geometryType={activeVectorLayer?.geometryType}
                pendingChanges={unsavedChangesCount}
                drawingActive={editing.active}
              />
              <EditToolbar
                active={editing.active}
                geometryType={activeVectorLayer?.geometryType}
                tool={editing.tool}
                onToolChange={editing.setTool}
                onStart={requestStartMapEditing}
                onSave={() => void editing.save()}
                onDiscard={editing.discard}
                onLeave={requestLeaveMapEditing}
                onDelete={editing.deleteSelected}
                canDelete={editing.selectedFid !== null}
                isSaving={editing.isSaving}
                canEdit={Boolean(activeVectorLayer)}
                snapEnabled={editing.snapEnabled}
                onToggleSnap={editing.toggleSnap}
                snapUnavailableReason={editing.snapUnavailableReason ?? undefined}
              />
              {!editing.active && (
                <>
                  {/* Not just !editing.active: an import lands new features into the
                      catalog while a table edit session is buffering its own pending
                      writes against it, which the two were never meant to race. */}
                  {!tableActive && (
                    <Button variant="outline" size="sm" onClick={() => setImportOpen(true)}>
                      <Upload className="size-3.5" />
                      Importieren
                    </Button>
                  )}
                  <Button variant="outline" size="sm" onClick={() => setCreateLayerOpen(true)}>
                    <Plus className="size-3.5" />
                    Neuer Layer
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => setGeoportalOpen(true)}>
                    <Globe className="size-3.5" />
                    Daten aus dem Geoportal Hamburg
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => setAddMapImageOpen(true)}>
                    <ImageIcon className="size-3.5" />
                    Eigener WMS-Dienst
                  </Button>
                </>
              )}
            </div>
          </>
        }
        leftDock={
          <div className="flex h-full min-h-0 flex-col">
            <div className="min-h-0 flex-1">
              <LayerTree
                projectId={projectId}
                activeLayerId={activeLayerId ?? null}
                onSelectLayer={selectLayer}
                onZoomToLayer={requestZoom}
                onImportClick={() => setImportOpen(true)}
                onCreateLayerClick={() => setCreateLayerOpen(true)}
                onGeoportalClick={() => setGeoportalOpen(true)}
                onAddMapImageClick={() => setAddMapImageOpen(true)}
                snapSources={editing.active ? editing.snapSourceLayerIds : null}
                onToggleSnapSource={editing.toggleSnapSource}
              />
            </div>
            {editing.active && (
              <div className="max-h-[55%] shrink-0 overflow-auto border-t">
                <div className="flex h-7 items-center border-b bg-muted/40 px-2 text-xs font-medium tracking-wide uppercase text-muted-foreground">
                  Attribute
                </div>
                <AttributeForm
                  fields={activeLayerDetail?.fields ?? []}
                  feature={editing.selectedFeature}
                />
              </div>
            )}
            {/* Shares the dock's lower half with the attribute form, and yields to it:
                while editing, the attributes of the selected object are what matters. */}
            {!editing.active && activeLayer && (
              <div className="max-h-[55%] shrink-0 overflow-auto border-t">
                <LayerProperties layer={activeLayer} projectId={projectId} />
                {/* No symbology for a Kartenbild (contract: "style fehlt") -- the panel
                    itself only ever takes a `VectorLayerSummary`. */}
                {activeVectorLayer && <SymbologyPanel layer={activeVectorLayer} projectId={projectId} />}
              </div>
            )}
          </div>
        }
        map={
          <ProjectMap
            project={project}
            zoomTo={zoomTo}
            remoteViewport={remoteViewport}
            activeLayer={activeLayer}
            // Identify would consume the same click the measuring and rectangle select
            // tools need.
            identifyEnabled={!editing.active && !measuring && !rectSelecting && !splittingLine}
          >
            {editing.active && activeVectorLayer && (
              <>
                <DrawController
                  layerId={activeVectorLayer.id}
                  geometryType={activeVectorLayer.geometryType}
                  tool={editing.tool}
                  onSelectFeature={editing.selectFeature}
                  reloadNonce={editing.reloadNonce}
                  snapEnabled={editing.snapEnabled}
                  onSnapTarget={editing.setSnapTarget}
                  onSnapUnavailable={editing.setSnapUnavailableReason}
                  snapSourceLayerIds={editing.snapSourceLayerIds}
                />
                <SnapMarker target={editing.snapTarget} />
                <EditingTileFilter layerId={activeVectorLayer.id} />
              </>
            )}
            {measuring && <MeasurementOverlay />}
            {rectSelecting && activeVectorLayer && <RectangleSelectTool layerId={activeVectorLayer.id} />}
            {/* Renders nothing until one of the two tools is running; the toolbar above
                decides that, and this is only where the map-side half of it lives. A
                Kartenbild never gets here: `StructureToolbar` above already renders
                nothing without a `geometryType`, so `phase` can never leave `idle`. */}
            {activeVectorLayer && (
              <StructureOverlay
                layerId={activeVectorLayer.id}
                projectId={projectId}
                fields={activeLayerDetail?.fields ?? []}
              />
            )}
          </ProjectMap>
        }
        attributes={
          <AttributeTable
            layerId={activeLayerId ?? null}
            layerName={activeLayer?.name}
            layerKind={activeLayer?.kind}
            layerFeatureCount={activeLayer?.featureCount}
            projectId={projectId}
            viewState={viewState}
            onZoomToFeature={zoomToFeature}
            onRequestEdit={requestStartTableEditing}
          />
        }
      />

      <DiscardEditsDialog
        open={pendingSwitch === 'toTable'}
        title="Zeichenmodus verlassen?"
        description={`Sie haben ${describeUnsavedChanges(editing.pending)} im Zeichenmodus. Diese ${unsavedChangesVerb(editing.pending)} verloren, wenn Sie jetzt die Tabelle bearbeiten.`}
        confirmLabel="Änderungen verwerfen"
        onConfirm={() => {
          editing.stop()
          if (activeLayerId) useTableEditing.getState().begin(activeLayerId)
          setPendingSwitch(null)
        }}
        onCancel={() => setPendingSwitch(null)}
      />
      <DiscardEditsDialog
        open={pendingSwitch === 'toMap'}
        title="Bearbeitungsmodus verlassen?"
        description={`Sie haben ${describeUnsavedChanges(tableChanges)} in der Tabelle. Diese ${unsavedChangesVerb(tableChanges)} verloren, wenn Sie jetzt den Zeichenmodus starten.`}
        confirmLabel="Änderungen verwerfen"
        onConfirm={() => {
          useTableEditing.getState().end()
          editing.start()
          setPendingSwitch(null)
        }}
        onCancel={() => setPendingSwitch(null)}
      />
      <DiscardEditsDialog
        open={confirmLeaveMap}
        title="Zeichenmodus verlassen?"
        description={`Sie haben ${describeUnsavedChanges(editing.pending)} im Zeichenmodus. Diese ${unsavedChangesVerb(editing.pending)} verloren.`}
        confirmLabel="Änderungen verwerfen"
        onConfirm={() => {
          editing.stop()
          setConfirmLeaveMap(false)
        }}
        onCancel={() => setConfirmLeaveMap(false)}
      />
      <DiscardEditsDialog
        open={leaveGuard.status === 'blocked'}
        title="Ungespeicherte Änderungen verwerfen?"
        description={`${describeUnsavedWork(unsavedWork)} verloren, wenn Sie jetzt fortfahren.`}
        confirmLabel="Änderungen verwerfen"
        onConfirm={() => {
          // Whichever mode is dirty is the one `hasPendingWork` blocked on -- ending both
          // unconditionally is harmless (a clean session simply resets to the same idle
          // state) and cheaper than working out which one it was.
          if (editing.active) editing.stop()
          if (tableActive) useTableEditing.getState().end()
          leaveGuard.proceed?.()
        }}
        onCancel={() => leaveGuard.reset?.()}
      />
    </>
  )
}

function ProjectLoadError({ error }: { error: Error }) {
  const router = useRouter()
  const notFound = error instanceof ApiError && error.status === 404

  return (
    <div className="flex h-dvh flex-col items-center justify-center gap-4 p-8 text-center">
      <div>
        <h1 className="font-medium">
          {notFound ? 'Projekt nicht gefunden' : 'Das Programm konnte das Projekt nicht laden'}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {notFound
            ? 'Es wurde vermutlich gelöscht.'
            : error.message}
        </p>
      </div>
      <div className="flex gap-2">
        {!notFound && (
          <Button variant="outline" onClick={() => router.invalidate()}>
            Erneut versuchen
          </Button>
        )}
        <Link to="/" className={buttonVariants()}>
          Zur Projektliste
        </Link>
      </div>
    </div>
  )
}
