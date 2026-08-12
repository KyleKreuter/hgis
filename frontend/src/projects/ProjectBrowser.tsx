import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Copy, Layers, MoreHorizontal, Pencil, Plus, Search, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { projectListQuery, type ProjectSummary } from '@/api/projects'
import { Brand } from '@/layout/Brand'
import { formatCount, formatRelative } from '@/lib/format'
import { CreateProjectDialog } from './CreateProjectDialog'
import { DeleteProjectDialog } from './DeleteProjectDialog'
import { RenameProjectDialog } from './RenameProjectDialog'
import { DuplicateProjectDialog } from './DuplicateProjectDialog'

export function ProjectBrowser() {
  const { data: projects, isLoading, isError, error } = useQuery(projectListQuery())

  const [search, setSearch] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [toDelete, setToDelete] = useState<ProjectSummary | null>(null)
  const [toRename, setToRename] = useState<ProjectSummary | null>(null)
  const [toDuplicate, setToDuplicate] = useState<ProjectSummary | null>(null)

  const filtered = useMemo(() => {
    if (!projects) return []
    const needle = search.trim().toLowerCase()
    if (!needle) return projects
    return projects.filter(
      (p) =>
        p.name.toLowerCase().includes(needle) ||
        p.description?.toLowerCase().includes(needle),
    )
  }, [projects, search])

  return (
    <div className="flex h-dvh flex-col overflow-hidden">
      <header className="flex h-12 shrink-0 items-center justify-between border-b px-4">
        <Brand />
        <Button size="sm" onClick={() => setCreateOpen(true)}>
          <Plus className="size-3.5" />
          Neues Projekt
        </Button>
      </header>

      <div className="mx-auto w-full max-w-4xl flex-1 overflow-y-auto p-6">
        {isError ? (
          <EmptyState
            title="Projekte lassen sich nicht laden"
            hint={error instanceof Error ? error.message : 'Läuft das Backend?'}
          />
        ) : isLoading ? (
          <div className="space-y-2">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : projects && projects.length === 0 ? (
          <FirstRun onCreate={() => setCreateOpen(true)} />
        ) : (
          <>
            <div className="relative mb-4 max-w-xs">
              <Search className="absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Suche…"
                className="pl-8"
              />
            </div>

            {filtered.length === 0 ? (
              <EmptyState
                title="Kein Treffer"
                hint={`Keine Projekte passen zu „${search}"`}
              />
            ) : (
              <div className="overflow-x-auto rounded border">
                <Table>
                  <TableHeader>
                    <TableRow className="hover:bg-transparent">
                      <TableHead>Name</TableHead>
                      <TableHead className="w-28">CRS</TableHead>
                      <TableHead className="w-20 text-right">Layer</TableHead>
                      <TableHead className="w-28 text-right">Objekte</TableHead>
                      <TableHead className="w-36">Zuletzt geöffnet</TableHead>
                      <TableHead className="w-10" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {filtered.map((project) => (
                      <ProjectRow
                        key={project.id}
                        project={project}
                        onRename={() => setToRename(project)}
                        onDuplicate={() => setToDuplicate(project)}
                        onDelete={() => setToDelete(project)}
                      />
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
          </>
        )}
      </div>

      <CreateProjectDialog open={createOpen} onOpenChange={setCreateOpen} />
      <DeleteProjectDialog project={toDelete} onOpenChange={() => setToDelete(null)} />
      <RenameProjectDialog project={toRename} onOpenChange={() => setToRename(null)} />
      <DuplicateProjectDialog project={toDuplicate} onOpenChange={() => setToDuplicate(null)} />
    </div>
  )
}

function ProjectRow({
  project,
  onRename,
  onDuplicate,
  onDelete,
}: {
  project: ProjectSummary
  onRename: () => void
  onDuplicate: () => void
  onDelete: () => void
}) {
  const navigate = useNavigate()

  const open = () =>
    navigate({ to: '/projects/$projectId', params: { projectId: project.id } })

  return (
    <TableRow
      className="cursor-pointer"
      // The whole row is the click target, but the name stays a real link so
      // keyboard navigation, middle click and "open in new tab" keep working.
      onClick={open}
    >
      <TableCell>
        <Link
          to="/projects/$projectId"
          params={{ projectId: project.id }}
          className="font-medium hover:underline"
          onClick={(e) => e.stopPropagation()}
        >
          {project.name}
        </Link>
        {project.description && (
          <div className="truncate text-xs text-muted-foreground">
            {project.description}
          </div>
        )}
      </TableCell>

      <TableCell className="text-muted-foreground tabular-nums">
        EPSG:{project.srid}
      </TableCell>

      {/* Numeric columns right aligned with tabular figures so digits line up
          across rows -- otherwise proportional numerals make columns ragged. */}
      <TableCell className="text-right tabular-nums">
        {project.layerCount === 0 ? (
          <span className="text-muted-foreground">-</span>
        ) : (
          formatCount(project.layerCount)
        )}
      </TableCell>

      <TableCell className="text-right tabular-nums">
        {project.featureCount === 0 ? (
          <span className="text-muted-foreground">-</span>
        ) : (
          formatCount(project.featureCount)
        )}
      </TableCell>

      <TableCell className="text-muted-foreground">
        {formatRelative(project.lastOpenedAt)}
      </TableCell>

      <TableCell className="text-right">
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Aktionen für ${project.name}`}
                onClick={(e) => e.stopPropagation()}
              >
                <MoreHorizontal className="size-3.5" />
              </Button>
            }
          />
          <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
            <DropdownMenuItem onClick={onRename}>
              <Pencil className="size-3.5" />
              Umbenennen
            </DropdownMenuItem>
            <DropdownMenuItem onClick={onDuplicate}>
              <Copy className="size-3.5" />
              Duplizieren
            </DropdownMenuItem>
            {/* No destructive variant: the UI stays monochrome. The warning is
                carried by the confirmation dialog, which names what is lost and
                demands the project name be typed. */}
            <DropdownMenuItem onClick={onDelete}>
              <Trash2 className="size-3.5" />
              Löschen
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </TableCell>
    </TableRow>
  )
}

/** First run: show the way forward instead of an empty table. */
function FirstRun({ onCreate }: { onCreate: () => void }) {
  return (
    <div className="flex flex-col items-center gap-4 py-20 text-center">
      <Layers className="size-8 text-muted-foreground" strokeWidth={1.25} />
      <div>
        <h2 className="font-medium">Noch kein Projekt</h2>
        <p className="mt-1 max-w-sm text-sm text-muted-foreground">
          Ein Projekt bündelt Layer, deren Darstellung und den zuletzt betrachteten
          Kartenausschnitt. Das ist vergleichbar mit einer Projektdatei in QGIS.
        </p>
      </div>
      <Button onClick={onCreate}>
        <Plus className="size-3.5" />
        Erstes Projekt anlegen
      </Button>
    </div>
  )
}

function EmptyState({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="py-20 text-center">
      <h2 className="font-medium">{title}</h2>
      <p className="mt-1 text-sm text-muted-foreground">{hint}</p>
    </div>
  )
}
