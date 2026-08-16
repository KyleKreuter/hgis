import {
  infiniteQueryOptions,
  queryOptions,
  useMutation,
  useQueryClient,
  type QueryClient,
  type UseMutationOptions,
} from '@tanstack/react-query'
import { api } from './client'
import { CLIENT_HEADER, CLIENT_ID } from './events'
import type { Job } from './imports'
import type { ViewStateDocument } from '@/state/viewState'

export interface ProjectSummary {
  id: string
  name: string
  description: string | null
  srid: number
  layerCount: number
  featureCount: number
  lastOpenedAt: string | null
  createdAt: string
  /** [lng, lat] in EPSG:4326, or null/missing while the project has never been viewed. */
  center?: [number, number] | null
  zoom?: number | null
  /** [minLng, minLat, maxLng, maxLat] in EPSG:4326, or null/missing without any layer. */
  extent?: [number, number, number, number] | null
  /**
   * Optional only so fixtures outside this package (CONTRACT.md phase 22) can build a
   * `ProjectSummary` without it -- the backend always sets it.
   */
  basemap?: string
}

/** One page of `GET /api/projects`. `nextCursor` is opaque: never read, only replayed. */
export interface ProjectPage {
  items: ProjectSummary[]
  nextCursor: string | null
}

export interface ProjectDetail extends ProjectSummary {
  basemap: string
  /** Opacity of the background map, 0..1. Always set -- a project has no "unset" state. */
  basemapOpacity: number
  /** [lng, lat] in EPSG:4326, or null while the project has never been viewed. */
  center: [number, number] | null
  zoom: number | null
  /** [minX, minY, maxX, maxY] in EPSG:4326 */
  extent: [number, number, number, number] | null
  updatedAt: string
}

export interface DeletionImpact {
  layerCount: number
  featureCount: number
}

export interface CreateProjectInput {
  name: string
  description?: string
  srid?: number
  basemap?: string
}

export interface UpdateProjectInput {
  name?: string
  description?: string
  basemap?: string
  basemapOpacity?: number
  center?: [number, number]
  zoom?: number
}

export interface DuplicateProjectInput {
  name?: string
}

export const projectKeys = {
  all: ['projects'] as const,
  /** One chain per search term, so a new search starts a fresh chain of pages. */
  list: (q: string) => [...projectKeys.all, 'list', q] as const,
  detail: (id: string) => ['projects', id] as const,
  deletionImpact: (id: string) => ['projects', id, 'deletion-impact'] as const,
  /** Mutation key, not a query key: it is what finds a project's patches in flight. */
  update: (id: string) => ['projects', id, 'update'] as const,
  viewState: (id: string) => ['projects', id, 'view-state'] as const,
}

/**
 * `projectKeys.all` is a prefix of every detail key, so invalidating it without this
 * would refetch the open project as well -- and a refetch landing in the middle of a
 * burst of patches is exactly what makes the map jump back to an older answer.
 * `exact: false` on purpose here: there is one list chain per search term, and every
 * one of them (not just the currently active search) needs to be marked stale.
 *
 * Exported because the same reasoning holds outside this file: `useApplyEdits`
 * (`api/edits.ts`) changes a project's feature count and extent, which the browser shows,
 * and nothing else about the project.
 */
export const LIST_ONLY = { queryKey: [...projectKeys.all, 'list'], exact: false } as const

/** Builds `GET /api/projects`, adding only the parameters that have a value. */
function projectsUrl(cursor: string | undefined, q: string): string {
  const params = new URLSearchParams()
  if (cursor) params.set('cursor', cursor)
  if (q.trim()) params.set('q', q.trim())
  const query = params.toString()
  return query ? `/api/projects?${query}` : '/api/projects'
}

/**
 * @param q search term, matched server-side against name and description (CONTRACT.md
 *   phase 22). Part of the query key so a new search begins its own chain of pages
 *   instead of appending to whatever the previous search had already loaded.
 */
export const projectListInfiniteQuery = (q: string) =>
  infiniteQueryOptions({
    queryKey: projectKeys.list(q),
    queryFn: ({ pageParam }) => api.get<ProjectPage>(projectsUrl(pageParam, q)),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  })

/**
 * @param open marks the project as opened, which reorders the browser. Only the
 *   workspace route should set this -- a plain read must not disturb the ordering.
 */
export const projectDetailQuery = (id: string, open = false) =>
  queryOptions({
    queryKey: projectKeys.detail(id),
    queryFn: () =>
      api.get<ProjectDetail>(`/api/projects/${id}${open ? '?open=true' : ''}`),
  })

export const deletionImpactQuery = (id: string) =>
  queryOptions({
    queryKey: projectKeys.deletionImpact(id),
    queryFn: () => api.get<DeletionImpact>(`/api/projects/${id}/deletion-impact`),
  })

export function useCreateProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateProjectInput) =>
      api.post<ProjectDetail>('/api/projects', input),
    onSuccess: (created) => {
      queryClient.setQueryData(projectKeys.detail(created.id), created)
      queryClient.invalidateQueries(LIST_ONLY)
    },
  })
}

/**
 * One PATCH applied to a cached project.
 *
 * Keys carrying `undefined` are dropped rather than merged: they mean "not part of
 * this patch", while the detail's own `description: null` means "explicitly empty",
 * and spreading the former over the latter would confuse the two. The cast is what
 * the narrowing costs -- `UpdateProjectInput` is a subset of `ProjectDetail` field by
 * field, which is what makes the merge sound in the first place.
 */
export function applyProjectPatch(
  project: ProjectDetail,
  input: UpdateProjectInput,
): ProjectDetail {
  const given = Object.fromEntries(
    Object.entries(input).filter(([, value]) => value !== undefined),
  )
  return { ...project, ...given } as ProjectDetail
}

/**
 * The server's answer to one patch, with every patch that is still in flight applied
 * on top of it.
 *
 * Without this the answer to the first of two quick changes -- two clicks in the
 * basemap menu, or a basemap change while the viewport is being saved -- overwrites
 * the second one's optimistic value, and the map visibly falls back to the previous
 * basemap until the second answer arrives. The patches are applied in the order they
 * were sent, so the newest value wins.
 */
export function reconcileProject(
  server: ProjectDetail,
  pending: readonly UpdateProjectInput[],
): ProjectDetail {
  return pending.reduce(applyProjectPatch, server)
}

/** The project's patches still in flight, newest last, without `exclude`. */
function pendingPatches(
  queryClient: QueryClient,
  id: string,
  exclude?: UpdateProjectInput,
): UpdateProjectInput[] {
  return queryClient
    .getMutationCache()
    .findAll({ mutationKey: projectKeys.update(id), status: 'pending' })
    .map((mutation) => mutation.state.variables as UpdateProjectInput)
    // The mutation running this callback still counts as pending; its own values are
    // already in the answer being reconciled, and a failed one must not be re-applied.
    .filter((variables) => variables !== undefined && variables !== exclude)
}

/** What a patch has to be able to roll back to when it fails. */
export interface ProjectPatchContext {
  previous: ProjectDetail | undefined
}

/**
 * The options behind `useUpdateProject`, separated so the cache strategy can be
 * exercised against a real QueryClient without a React tree.
 */
export function projectUpdateOptions(
  queryClient: QueryClient,
  id: string,
): UseMutationOptions<ProjectDetail, Error, UpdateProjectInput, ProjectPatchContext> {
  return {
    mutationKey: projectKeys.update(id),
    /**
     * One queue per project: patches to the same project run one after another rather
     * than in parallel. The basemap picker and the viewport persistence write to the
     * same row from two components, and PATCHes overtaking each other let the older
     * write win -- on the server as well as in the cache.
     */
    scope: { id: `project-update-${id}` },
    mutationFn: (input: UpdateProjectInput) =>
      api.patch<ProjectDetail>(`/api/projects/${id}`, input),
    /**
     * Writes the change into the cached project before the request goes out, so a
     * setting that the map renders from -- the basemap above all -- takes effect on
     * the click instead of one round trip later. This still runs immediately for a
     * queued patch; only the request itself waits.
     */
    onMutate: async (input: UpdateProjectInput) => {
      // Without this an in-flight GET could land after the patch and undo it.
      await queryClient.cancelQueries({ queryKey: projectKeys.detail(id) })
      const previous = queryClient.getQueryData<ProjectDetail>(projectKeys.detail(id))
      if (previous) {
        queryClient.setQueryData<ProjectDetail>(
          projectKeys.detail(id),
          applyProjectPatch(previous, input),
        )
      }
      return { previous }
    },
    onError: (_error, input, context) => {
      if (!context?.previous) return
      // Rolls back this patch only. The state it captured predates any patch that was
      // sent after it, so those are put back on top instead of being lost with it.
      queryClient.setQueryData(
        projectKeys.detail(id),
        reconcileProject(context.previous, pendingPatches(queryClient, id, input)),
      )
    },
    onSuccess: (updated, input) => {
      queryClient.setQueryData(
        projectKeys.detail(id),
        reconcileProject(updated, pendingPatches(queryClient, id, input)),
      )
      queryClient.invalidateQueries(LIST_ONLY)
    },
  }
}

export function useUpdateProject(id: string) {
  const queryClient = useQueryClient()
  return useMutation(projectUpdateOptions(queryClient, id))
}

export function useDeleteProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/projects/${id}`),
    onSuccess: (_result, id) => {
      queryClient.removeQueries({ queryKey: projectKeys.detail(id) })
      queryClient.invalidateQueries(LIST_ONLY)
    },
  })
}

export function useDuplicateProject(projectId: string) {
  return useMutation({
    mutationFn: (input: DuplicateProjectInput) =>
      api.post<Job>(`/api/projects/${projectId}/duplicate`, input),
  })
}

/** Used by the workspace route loader so panels never mount against empty data. */
export function ensureProjectLoaded(queryClient: QueryClient, id: string) {
  return queryClient.ensureQueryData(projectDetailQuery(id, true))
}

/**
 * The project's working state (CONTRACT.md phase 17, schema B): active layer, and per
 * layer, its sort, its filter/search and its selection. A project that never saved one
 * gets the server's own empty document back, never a 404 -- so callers can treat "no
 * saved state" and "an empty saved state" as the same thing.
 */
export const viewStateQuery = (id: string) =>
  queryOptions({
    queryKey: projectKeys.viewState(id),
    queryFn: () => api.get<ViewStateDocument>(`/api/projects/${id}/view-state`),
  })

/**
 * Writes the working-state document. No optimistic cache update: unlike the project
 * patch above, nothing in the workspace renders from this query while the session is
 * open -- `state/useViewState.ts` keeps its own copy for that -- so there is nothing an
 * optimistic write would make feel faster.
 *
 * The write names this tab, and that is what keeps the live channel from turning it into
 * a read: the event this write produces comes back carrying `CLIENT_ID`, and a client
 * that finds its own name there already holds the state (`api/events.ts`).
 */
export function useSaveViewState(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (document: ViewStateDocument) =>
      api.put<void>(`/api/projects/${id}/view-state`, document, { [CLIENT_HEADER]: CLIENT_ID }),
    onSuccess: (_result, document) => {
      queryClient.setQueryData(projectKeys.viewState(id), document)
    },
  })
}
