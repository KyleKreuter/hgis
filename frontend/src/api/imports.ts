import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { layerKeys } from './layers'
import { projectKeys } from './projects'

export type JobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface Job {
  id: string
  type: string
  status: JobStatus
  filename: string | null
  processedCount: number
  /**
   * How many features the source announced up front. Null whenever the reader cannot
   * say -- a streamed GeoJSON has no count until it has been read to the end -- which
   * is why the progress bar has to cope with an unknown total.
   */
  totalCount: number | null
  skippedCount: number
  outputLayerId: string | null
  message: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

export interface StartImportInput {
  file: File
  /** Layer name; the backend falls back to the file's base name when omitted. */
  name?: string
  /** Source CRS override, needed when the file carries none (CSV always, .prj-less shapefiles). */
  srid?: number
  /** Character set override for DBF attribute values. */
  charset?: string
}

export function isJobFinished(status: JobStatus | undefined): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED'
}

export const jobKeys = {
  detail: (jobId: string) => ['jobs', jobId] as const,
}

const jobQuery = (jobId: string) =>
  queryOptions({
    queryKey: jobKeys.detail(jobId),
    queryFn: () => api.get<Job>(`/api/jobs/${jobId}`),
    // Polling has to survive the client-wide staleTime of 30s, which would otherwise
    // serve the first response over and over and freeze the progress display.
    staleTime: 0,
    refetchInterval: (query) => (isJobFinished(query.state.data?.status) ? false : 600),
  })

/**
 * Polls one job until it reaches a terminal state. Pass null to stop polling entirely --
 * the dialog uses that before an upload has been started and after it has been dismissed.
 */
export function useImportJob(jobId: string | null) {
  return useQuery({
    ...jobQuery(jobId ?? 'none'),
    enabled: jobId !== null,
  })
}

/**
 * Starts an import. Note what is and is not asynchronous here: the endpoint opens the
 * uploaded file synchronously, so an unreadable file, an unknown format or an
 * implausible CRS come back as a rejected mutation with a readable message. Only the
 * writing runs as a job, which is what {@link useImportJob} then follows.
 */
export function useStartImport(projectId: string) {
  return useMutation({
    mutationFn: ({ file, name, srid, charset }: StartImportInput) => {
      const form = new FormData()
      form.append('file', file)
      if (name?.trim()) form.append('name', name.trim())
      if (srid !== undefined) form.append('srid', String(srid))
      if (charset) form.append('charset', charset)
      return api.postForm<Job>(`/api/projects/${projectId}/imports`, form)
    },
  })
}

/**
 * Call once an import has succeeded: the new layer has to appear in the tree and on the
 * map, and the project's feature totals and extent changed with it.
 */
export function useRefreshAfterImport(projectId: string) {
  const queryClient = useQueryClient()
  return () => {
    queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
    // ['projects'] is a prefix of every project key, so this covers the browser list
    // and this project's detail (whose extent and feature totals just changed).
    queryClient.invalidateQueries({ queryKey: projectKeys.all })
  }
}
