import {
  queryOptions,
  useMutation,
  useQueryClient,
  type QueryClient,
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
}

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
      queryClient.invalidateQueries({ queryKey: projectKeys.all })
    },
  })
}

export function useUpdateProject(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: UpdateProjectInput) =>
      api.patch<ProjectDetail>(`/api/projects/${id}`, input),
    onSuccess: (updated) => {
      queryClient.setQueryData(projectKeys.detail(id), updated)
      queryClient.invalidateQueries({ queryKey: projectKeys.all })
    },
  })
}

export function useDeleteProject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/projects/${id}`),
    onSuccess: (_result, id) => {
      queryClient.removeQueries({ queryKey: projectKeys.detail(id) })
      queryClient.invalidateQueries({ queryKey: projectKeys.all })
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
