import { useCallback, useEffect, useState } from 'react'
import { createFileRoute, Link, useBlocker, useNavigate, useRouter } from '@tanstack/react-router'
import { useQuery, useQueryClient, useSuspenseQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, Plus, Upload } from 'lucide-react'
import { WorkspaceLayout } from '@/layout/WorkspaceLayout'
import { Separator } from '@/components/ui/separator'
import { Button, buttonVariants } from '@/components/ui/button'
import { ApiError } from '@/api/client'
import { ensureProjectLoaded, projectDetailQuery } from '@/api/projects'
import { countChanges, useEditing } from '@/state/editing'
import {
  describeUnsavedChanges,
  hasUnsavedChanges,
  totalUnsavedChanges,
  unsavedChangesVerb,
} from '@/state/unsavedChanges'
import { CreateLayerDialog, ImportDialog, LayerProperties, LayerTree } from '@/layers'
import { ProjectMap, type ZoomRequest } from '@/map'
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
  useEditSession,
} from '@/editing'
import { MeasurementOverlay, MeasurementToolbar, useIsMeasuring } from '@/measurement'
import { layerDetailQuery, layerListQuery } from '@/api/layers'
import { featureDetailQuery } from '@/api/features'
import { useSelection } from '@/state/selection'
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
  // A counter, not a timestamp: zooming to the same layer twice has to produce a new
  // request object, and a counter does that without depending on the clock.
  const [zoomTo, setZoomTo] = useState<ZoomRequest | null>(null)
  const clearSelection = useSelection((state) => state.clear)
  const editing = useEditSession({ layerId: activeLayerId ?? null, projectId })
  // Only the on/off fact, not the running measurement -- the sketch changes with every
  // mouse move, and re-rendering the whole workspace for that would be absurd.
  const measuring = useIsMeasuring()
  const rectSelecting = useIsRectangleSelecting()
  const tableActive = useIsTableEditing()
  const tableChanges = useTableEditing(tableChangeCount)
  const tableLayerId = useTableEditing((state) => state.layerId)
  // Which dirty session a mode switch would discard -- null means neither is blocked.
  const [pendingSwitch, setPendingSwitch] = useState<'toTable' | 'toMap' | null>(null)
  // Only the detail carries the field list the attribute form is generated from.
  const { data: activeLayerDetail } = useQuery({
    ...layerDetailQuery(activeLayerId ?? ''),
    enabled: Boolean(activeLayerId),
  })

  const activeLayer = layers?.find((layer) => layer.id === activeLayerId) ?? null

  function requestZoom(extent: [number, number, number, number]) {
    setZoomTo((previous) => ({ extent, nonce: (previous?.nonce ?? 0) + 1 }))
  }

  function selectLayer(layerId: string | null) {
    // A fid means nothing outside its layer, so a selection cannot survive the switch.
    clearSelection()
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

  // Reads both buffers fresh at call time rather than closing over `editing.pending` /
  // `tableChanges` -- `shouldBlockFn` and `enableBeforeUnload` both run outside React's
  // render cycle, and a save that just happened has to be visible immediately, not
  // whatever was current when this function was created.
  const hasPendingWork = useCallback(
    () =>
      hasUnsavedChanges(
        countChanges(useEditing.getState().buffer),
        tableChangeCount(useTableEditing.getState()),
      ),
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

  return (
    <>
      <ImportDialog projectId={projectId} open={importOpen} onOpenChange={setImportOpen} />
      <CreateLayerDialog
        projectId={projectId}
        open={createLayerOpen}
        onOpenChange={setCreateLayerOpen}
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
            <span className="font-medium">{project.name}</span>
            {/* The variant must match the primitive's data-vertical:self-stretch --
                tailwind-merge treats prefixed and unprefixed utilities as separate
                groups, so a bare self-center would not replace it. */}
            <Separator orientation="vertical" className="h-4 data-vertical:self-center" />
            <span className="text-xs text-muted-foreground">EPSG:{project.srid}</span>

            <div className="ml-auto flex items-center gap-2">
              {/* Measuring answers a question about the map and writes nothing, so it
                  stands before the editing tools rather than inside them. */}
              <MeasurementToolbar disabled={editing.active} />
              <Separator orientation="vertical" className="h-4 data-vertical:self-center" />
              <RectangleSelectToolbar disabled={editing.active} canUse={Boolean(activeLayer)} />
              <Separator orientation="vertical" className="h-4 data-vertical:self-center" />
              <EditToolbar
                active={editing.active}
                geometryType={activeLayer?.geometryType}
                tool={editing.tool}
                onToolChange={editing.setTool}
                onStart={requestStartMapEditing}
                onSave={() => void editing.save()}
                onDiscard={editing.discard}
                onDelete={editing.deleteSelected}
                canDelete={editing.selectedFid !== null}
                isSaving={editing.isSaving}
                canEdit={Boolean(activeLayer)}
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
                <SymbologyPanel layer={activeLayer} projectId={projectId} />
              </div>
            )}
          </div>
        }
        map={
          <ProjectMap
            project={project}
            zoomTo={zoomTo}
            activeLayerId={activeLayerId ?? null}
            // Identify would consume the same click the measuring and rectangle select
            // tools need.
            identifyEnabled={!editing.active && !measuring && !rectSelecting}
          >
            {editing.active && activeLayer && (
              <>
                <DrawController
                  layerId={activeLayer.id}
                  geometryType={activeLayer.geometryType}
                  tool={editing.tool}
                  onSelectFeature={editing.selectFeature}
                  reloadNonce={editing.reloadNonce}
                  snapEnabled={editing.snapEnabled}
                  onSnapTarget={editing.setSnapTarget}
                  onSnapUnavailable={editing.setSnapUnavailableReason}
                  snapSourceLayerIds={editing.snapSourceLayerIds}
                />
                <SnapMarker target={editing.snapTarget} />
                <EditingTileFilter layerId={activeLayer.id} />
              </>
            )}
            {measuring && <MeasurementOverlay />}
            {rectSelecting && activeLayer && <RectangleSelectTool layerId={activeLayer.id} />}
          </ProjectMap>
        }
        attributes={
          <AttributeTable
            layerId={activeLayerId ?? null}
            layerName={activeLayer?.name}
            projectId={projectId}
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
        open={leaveGuard.status === 'blocked'}
        title="Ungespeicherte Änderungen verwerfen?"
        description={`${describeUnsavedChanges(unsavedChangesCount)} ${unsavedChangesVerb(unsavedChangesCount)} verloren, wenn Sie jetzt fortfahren.`}
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
