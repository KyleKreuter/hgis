import {
  queryOptions,
  useMutation,
  useQueryClient,
  type QueryClient,
  type UseMutationOptions,
} from '@tanstack/react-query'
import { api } from './client'
import type { Job } from './imports'

export interface ProjectSummary {
  id: string
  name: string
  description: string | null
  srid: number
  layerCount: number
  featureCount: number
  lastOpenedAt: string | null
  createdAt: string
}

export interface ProjectDetail extends ProjectSummary {
  basemap: string
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
  center?: [number, number]
  zoom?: number
}

export interface DuplicateProjectInput {
  name?: string
}

export const projectKeys = {
  all: ['projects'] as const,
  detail: (id: string) => ['projects', id] as const,
  deletionImpact: (id: string) => ['projects', id, 'deletion-impact'] as const,
  /** Mutation key, not a query key: it is what finds a project's patches in flight. */
  update: (id: string) => ['projects', id, 'update'] as const,
}

/**
 * `projectKeys.all` is a prefix of every detail key, so invalidating it without this
 * would refetch the open project as well -- and a refetch landing in the middle of a
 * burst of patches is exactly what makes the map jump back to an older answer.
 */
const LIST_ONLY = { queryKey: projectKeys.all, exact: true } as const

export const projectListQuery = () =>
  queryOptions({
    queryKey: projectKeys.all,
    queryFn: () => api.get<ProjectSummary[]>('/api/projects'),
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
