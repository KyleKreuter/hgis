import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Copy, Layers, MoreHorizontal, Pencil, Plus, Search, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { projectListInfiniteQuery, type ProjectSummary } from '@/api/projects'
import { resolveBasemap, type BasemapDefinition } from '@/map/basemap'
import { Brand } from '@/layout/Brand'
import { formatCount, formatRelative } from '@/lib/format'
import { MapPreview } from './MapPreview'
import { CreateProjectDialog } from './CreateProjectDialog'
import { DeleteProjectDialog } from './DeleteProjectDialog'
import { RenameProjectDialog } from './RenameProjectDialog'
import { DuplicateProjectDialog } from './DuplicateProjectDialog'

/** Typing pause before a search runs -- see CONTRACT.md phase 22. */
const SEARCH_DEBOUNCE_MS = 300
/** How many placeholder tiles stand in for the page that is still loading. */
const SKELETON_COUNT = 8

export function ProjectBrowser() {
  // The input's own value, lifted to `search` only after a pause -- searching on every
  // keystroke would fire a request per letter for a query nobody has finished typing.
  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')

  useEffect(() => {
    const timer = setTimeout(() => setSearch(searchInput), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [searchInput])

  function clearSearch() {
    // Bypasses the debounce: a click on "Suche leeren" is already the decided action,
    // not a keystroke to wait out.
    setSearchInput('')
    setSearch('')
  }

  const {
    data,
    isLoading,
    isError,
    error,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery({
    ...projectListInfiniteQuery(search),
    // Keeps the previous page's tiles on screen while a new search is in flight --
    // without this the grid would collapse to skeletons on every keystroke.
    placeholderData: keepPreviousData,
  })

  const [createOpen, setCreateOpen] = useState(false)
  const [toDelete, setToDelete] = useState<ProjectSummary | null>(null)
  const [toRename, setToRename] = useState<ProjectSummary | null>(null)
  const [toDuplicate, setToDuplicate] = useState<ProjectSummary | null>(null)

  const projects = useMemo(() => data?.pages.flatMap((page) => page.items) ?? [], [data])

  const sentinelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel || !hasNextPage || isFetchingNextPage) return

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) fetchNextPage()
      },
      // Starts the fetch a little before the sentinel is actually on screen, so the
      // next tiles are ready by the time scrolling reaches them.
      { rootMargin: '200px' },
    )
    observer.observe(sentinel)
    return () => observer.disconnect()
    // `isFetchingNextPage` belongs in here, and leaving it out is the bug this comment
    // exists to prevent coming back. An IntersectionObserver reports a *crossing*, not a
    // state: once the sentinel is on screen it never fires again on its own. Whenever a
    // freshly loaded page does not push the sentinel out of view -- a short list, a tall
    // window, a wide screen fitting a whole page at once -- loading would stop dead with
    // pages still to come, and no amount of scrolling would restart it, because there is
    // nothing to scroll. Re-running the effect after each fetch builds a new observer,
    // and a new observer reports the sentinel's current position immediately, so the
    // chain keeps going until the viewport is genuinely full.
    //
    // Not covered by a test: this project runs Vitest without jsdom (`.ts` only), so
    // there is no DOM to observe and no component to render. It was found in the browser
    // and has to be re-checked there.
  }, [hasNextPage, isFetchingNextPage, fetchNextPage])

  // One notice per background map actually in use among the loaded tiles, not one per
  // tile -- CONTRACT.md phase 22 asks for the credit once on the page.
  const attributions = useMemo(() => {
    const byId = new Map<string, BasemapDefinition>()
    for (const project of projects) {
      const basemap = resolveBasemap(project.basemap)
      if (basemap.attribution.length > 0) byId.set(basemap.id, basemap)
    }
    return [...byId.values()]
  }, [projects])

  const isFirstLoad = isLoading && projects.length === 0

  return (
    <div className="flex h-dvh flex-col overflow-hidden">
      <header className="flex h-12 shrink-0 items-center justify-between border-b px-4">
        <Brand />
        <Button size="sm" onClick={() => setCreateOpen(true)}>
          <Plus className="size-3.5" />
          Neues Projekt
        </Button>
      </header>

      <div className="mx-auto w-full max-w-6xl flex-1 overflow-y-auto p-6">
        {isError ? (
          <EmptyState
            title="Das Programm konnte die Projekte nicht laden"
            hint={error instanceof Error ? error.message : 'Läuft das Backend?'}
          />
        ) : isFirstLoad ? (
          <TileGrid>
            {Array.from({ length: SKELETON_COUNT }, (_, index) => (
              <TileSkeleton key={index} />
            ))}
          </TileGrid>
        ) : projects.length === 0 && !search ? (
          <FirstRun onCreate={() => setCreateOpen(true)} />
        ) : (
          <>
            <div className="relative mb-4 max-w-xs">
              <Search className="absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Suche…"
                className="pl-8"
              />
            </div>

            {projects.length === 0 ? (
              <EmptyState
                title="Kein Treffer"
                hint={`Keine Projekte passen zu „${search}“`}
                action={
                  <Button variant="outline" size="sm" onClick={clearSearch}>
                    Suche leeren
                  </Button>
                }
              />
            ) : (
              <>
                <TileGrid>
                  {projects.map((project) => (
                    <ProjectTile
                      key={project.id}
                      project={project}
                      onRename={() => setToRename(project)}
                      onDuplicate={() => setToDuplicate(project)}
                      onDelete={() => setToDelete(project)}
                    />
                  ))}
                  {isFetchingNextPage &&
                    Array.from({ length: 4 }, (_, index) => (
                      <TileSkeleton key={`loading-${index}`} />
                    ))}
                </TileGrid>

                {/* Observed to trigger the next page. Zero height: it is a trigger, not
                    a layout element -- the loading tiles above already show how much
                    more is coming. */}
                <div ref={sentinelRef} />

                {!hasNextPage && (
                  <p className="mt-6 text-center text-xs text-muted-foreground">
                    Alle Projekte geladen
                  </p>
                )}
              </>
            )}
          </>
        )}

        {attributions.length > 0 && (
          <p className="mt-6 text-center text-xs text-muted-foreground">
            {attributions.map((basemap, index) => (
              <span key={basemap.id}>
                {index > 0 && ' · '}
                {basemap.attribution.map((part) =>
                  part.href ? (
                    <a
                      key={part.text}
                      href={part.href}
                      target="_blank"
                      rel="noreferrer"
                      className="underline underline-offset-2 hover:text-foreground"
                    >
                      {part.text}
                    </a>
                  ) : (
                    <span key={part.text}>{part.text}</span>
                  ),
                )}
              </span>
            ))}
          </p>
        )}
      </div>

      <CreateProjectDialog open={createOpen} onOpenChange={setCreateOpen} />
      <DeleteProjectDialog project={toDelete} onOpenChange={() => setToDelete(null)} />
      <RenameProjectDialog project={toRename} onOpenChange={() => setToRename(null)} />
      <DuplicateProjectDialog project={toDuplicate} onOpenChange={() => setToDuplicate(null)} />
    </div>
  )
}

/** Responsive tile grid: one column on narrow screens, up to four on wide ones. */
function TileGrid({ children }: { children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
      {children}
    </div>
  )
}

function ProjectTile({
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
    <div
      className="flex cursor-pointer flex-col overflow-hidden rounded-xl bg-card text-sm text-card-foreground ring-1 ring-foreground/10 transition-colors hover:bg-muted/50"
      // The whole tile is the click target, but the name stays a real link so keyboard
      // navigation, middle click and "open in new tab" keep working.
      onClick={open}
    >
      <MapPreview project={project} />

      <div className="flex flex-1 flex-col gap-2 p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <Link
              to="/projects/$projectId"
              params={{ projectId: project.id }}
              title={project.name}
              className="block truncate font-medium hover:underline"
              onClick={(e) => e.stopPropagation()}
            >
              {project.name}
            </Link>
            {project.description && (
              <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">
                {project.description}
              </p>
            )}
          </div>

          <DropdownMenu>
            <DropdownMenuTrigger
              render={
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Aktionen für ${project.name}`}
                  className="shrink-0"
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
              {/* No destructive variant: the UI stays monochrome. The warning is carried
                  by the confirmation dialog, which names what is lost and demands the
                  project name be typed. */}
              <DropdownMenuItem onClick={onDelete}>
                <Trash2 className="size-3.5" />
                Löschen
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>

        <div className="mt-auto flex items-center gap-3 text-xs text-muted-foreground tabular-nums">
          <span>{project.layerCount === 0 ? '-' : formatCount(project.layerCount)} Layer</span>
          <span>
            {project.featureCount === 0 ? '-' : formatCount(project.featureCount)} Objekte
          </span>
        </div>

        <div className="text-xs text-muted-foreground">{formatRelative(project.lastOpenedAt)}</div>
      </div>
    </div>
  )
}

function TileSkeleton() {
  return (
    <div className="overflow-hidden rounded-xl bg-card ring-1 ring-foreground/10">
      <Skeleton className="aspect-video w-full rounded-none" />
      <div className="space-y-2 p-4">
        <Skeleton className="h-4 w-2/3" />
        <Skeleton className="h-3 w-full" />
        <Skeleton className="h-3 w-1/3" />
      </div>
    </div>
  )
}

/** First run: show the way forward instead of an empty grid. */
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

function EmptyState({ title, hint, action }: { title: string; hint: string; action?: ReactNode }) {
  return (
    <div className="py-20 text-center">
      <h2 className="font-medium">{title}</h2>
      <p className="mt-1 text-sm text-muted-foreground">{hint}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}
