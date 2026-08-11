import { useState } from 'react'
import { createFileRoute, Link, useNavigate, useRouter } from '@tanstack/react-router'
import { useQuery, useQueryClient, useSuspenseQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, Upload } from 'lucide-react'
import { WorkspaceLayout } from '@/layout/WorkspaceLayout'
import { Separator } from '@/components/ui/separator'
import { Button, buttonVariants } from '@/components/ui/button'
import { ApiError } from '@/api/client'
import { ensureProjectLoaded, projectDetailQuery } from '@/api/projects'
import { ImportDialog, LayerProperties, LayerTree } from '@/layers'
import { ProjectMap, type ZoomRequest } from '@/map'
import { SymbologyPanel } from '@/styling'
import { AttributeTable } from '@/table'
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
  // A counter, not a timestamp: zooming to the same layer twice has to produce a new
  // request object, and a counter does that without depending on the clock.
  const [zoomTo, setZoomTo] = useState<ZoomRequest | null>(null)
  const clearSelection = useSelection((state) => state.clear)
  const editing = useEditSession({ layerId: activeLayerId ?? null, projectId })
  // Only the on/off fact, not the running measurement -- the sketch changes with every
  // mouse move, and re-rendering the whole workspace for that would be absurd.
  const measuring = useIsMeasuring()
  const rectSelecting = useIsRectangleSelecting()
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
      toast.error('Objekt konnte nicht geladen werden')
    }
  }

  return (
    <>
      <ImportDialog projectId={projectId} open={importOpen} onOpenChange={setImportOpen} />
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
                onStart={editing.start}
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
                <Button variant="outline" size="sm" onClick={() => setImportOpen(true)}>
                  <Upload className="size-3.5" />
                  Importieren
                </Button>
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
                  onSelectFeature={editing.setSelectedFid}
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
            onZoomToFeature={zoomToFeature}
          />
        }
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
          {notFound ? 'Projekt nicht gefunden' : 'Projekt konnte nicht geladen werden'}
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
