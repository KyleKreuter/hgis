import { useState } from 'react'
import { createFileRoute, Link, useNavigate, useRouter } from '@tanstack/react-router'
import { useSuspenseQuery } from '@tanstack/react-query'
import { ArrowLeft, Upload } from 'lucide-react'
import { WorkspaceLayout } from '@/layout/WorkspaceLayout'
import { Separator } from '@/components/ui/separator'
import { Button, buttonVariants } from '@/components/ui/button'
import { ApiError } from '@/api/client'
import { ensureProjectLoaded, projectDetailQuery } from '@/api/projects'
import { ImportDialog, LayerTree } from '@/layers'
import { ProjectMap, type ZoomRequest } from '@/map'

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
 * The workspace shell. Later phases fill the panels:
 *   left dock   layer tree and layer properties (phase 4)
 *   map         MapLibre canvas with vector tile sources (phase 3)
 *   attributes  virtualised attribute table (phase 5)
 */
function Workspace() {
  const { projectId } = Route.useParams()
  const { layer: activeLayerId } = Route.useSearch()
  const navigate = useNavigate({ from: Route.fullPath })
  const { data: project } = useSuspenseQuery(projectDetailQuery(projectId, true))
  const [importOpen, setImportOpen] = useState(false)
  // A counter, not a timestamp: zooming to the same layer twice has to produce a new
  // request object, and a counter does that without depending on the clock.
  const [zoomTo, setZoomTo] = useState<ZoomRequest | null>(null)

  function selectLayer(layerId: string | null) {
    navigate({ search: { layer: layerId ?? undefined }, replace: true })
  }

  return (
    <>
      <ImportDialog projectId={projectId} open={importOpen} onOpenChange={setImportOpen} />
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

            <Button
              variant="outline"
              size="sm"
              className="ml-auto"
              onClick={() => setImportOpen(true)}
            >
              <Upload className="size-3.5" />
              Importieren
            </Button>
          </>
        }
        leftDock={
          <LayerTree
            projectId={projectId}
            activeLayerId={activeLayerId ?? null}
            onSelectLayer={selectLayer}
            onZoomToLayer={(extent) =>
              setZoomTo((previous) => ({ extent, nonce: (previous?.nonce ?? 0) + 1 }))
            }
            onImportClick={() => setImportOpen(true)}
          />
        }
        map={<ProjectMap project={project} zoomTo={zoomTo} />}
        attributes={<PanelStub title="Attribute" note="Attributtabelle folgt in Phase 5" />}
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

function PanelStub({ title, note }: { title: string; note: string }) {
  return (
    <div className="flex h-full flex-col">
      <div className="flex h-7 shrink-0 items-center border-b bg-muted/40 px-2 text-xs font-medium tracking-wide uppercase text-muted-foreground">
        {title}
      </div>
      <div className="flex flex-1 items-center justify-center p-4">
        <p className="text-sm text-muted-foreground">{note}</p>
      </div>
    </div>
  )
}
