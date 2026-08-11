import { useRef } from 'react'
import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { layerKeys, type GeometryType } from './layers'
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
  outputProjectId: string | null
  message: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

interface ImportOptions {
  /** Layer name; the backend falls back to the file's base name when omitted. */
  name?: string
  /** Source CRS override, needed when the file carries none (CSV always, .prj-less shapefiles). */
  srid?: number
  /** Character set override for DBF attribute values. */
  charset?: string
}

/**
 * Exactly one source, never both: the file itself, or the id an inspection stored it
 * under. Modelled as a union so the choice cannot be got wrong at a call site.
 */
export type StartImportInput = ImportOptions &
  ({ file: File; uploadId?: never } | { uploadId: string; file?: never })

/** How the source CRS was arrived at. GUESSED is the one that needs a second look. */
export type CrsConfidence = 'DECLARED' | 'ASSUMED' | 'GUESSED'

export interface InspectedField {
  /** Original name from the file, not the column name the import will derive from it. */
  name: string
  /** PostgreSQL target type, as the import would use it. */
  dataType: string
  /**
   * Up to ten values in file order. A null stays null and never becomes "" -- a missing
   * value and a blank one are different states (plan section D.4).
   */
  sampleValues: (string | null)[]
}

export interface Inspection {
  uploadId: string
  /** Original name as uploaded. */
  filename: string
  geometryType: GeometryType
  /** Null whenever the format does not announce a count up front, as for streamed GeoJSON. */
  featureCount: number | null
  /** Null when the format pins its own encoding -- GeoPackage, GeoJSON -- and there is nothing to decide. */
  charset: string | null
  srid: number
  crsConfidence: CrsConfidence
  /** [minLng, minLat, maxLng, maxLat] in WGS 84 whatever the source CRS, or null. */
  extentWgs84: [number, number, number, number] | null
  fields: InspectedField[]
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
export function useJob(jobId: string | null) {
  return useQuery({
    ...jobQuery(jobId ?? 'none'),
    enabled: jobId !== null,
  })
}

/** Kept for import call sites; jobs are shared by imports and project duplication. */
export const useImportJob = useJob

/**
 * Starts an import. Note what is and is not asynchronous here: the endpoint opens the
 * uploaded file synchronously, so an unreadable file, an unknown format or an
 * implausible CRS come back as a rejected mutation with a readable message. Only the
 * writing runs as a job, which is what {@link useImportJob} then follows.
 */
export function useStartImport(projectId: string) {
  return useMutation({
    mutationFn: ({ file, uploadId, name, srid, charset }: StartImportInput) => {
      const form = new FormData()
      // An uploadId spares the second transfer of a file the inspection already carried
      // over; at the permitted 500 MB that is the difference between usable and not.
      if (uploadId) form.append('uploadId', uploadId)
      else if (file) form.append('file', file)
      if (name?.trim()) form.append('name', name.trim())
      if (srid !== undefined) form.append('srid', String(srid))
      if (charset) form.append('charset', charset)
      return api.postForm<Job>(`/api/projects/${projectId}/imports`, form)
    },
  })
}

const inspectKey = (projectId: string, selection: number, srid?: number, charset?: string) =>
  ['imports', 'inspect', projectId, selection, srid ?? null, charset ?? null] as const

/**
 * Numbers every file ever picked in this session.
 *
 * Module scope on purpose: a per-component counter would start over when the dialog is
 * mounted again and hand out a key whose cached inspection points at an upload the
 * import has long since consumed.
 */
let selections = 0

/**
 * Inspects a file before anything is written: which fields with which values arrive,
 * which encoding was used, where the data lands (plan sections A.3 and A.7).
 *
 * The first call carries the file, every later one only the `uploadId` the server
 * answered with -- correcting the encoding must not re-upload the file. That id
 * therefore lives in a ref and not in the query key: in the key it would change the
 * moment the first inspection returns and immediately start a second upload.
 *
 * Inspecting creates no job and writes nothing; only the import does.
 */
export function useInspection(
  projectId: string,
  file: File | null,
  srid?: number,
  charset?: string,
) {
  const uploadId = useRef<string | null>(null)
  const inspected = useRef<File | null>(null)
  /** Which selection the cached inspections below belong to. */
  const selection = useRef(0)

  if (inspected.current !== file) {
    inspected.current = file
    // The stored upload belongs to the previous file and may already have been imported.
    uploadId.current = null
    selection.current = ++selections
  }

  const current = selection.current

  return useQuery({
    queryKey: inspectKey(projectId, current, srid, charset),
    queryFn: async ({ signal }) => {
      const form = new FormData()
      if (uploadId.current) form.append('uploadId', uploadId.current)
      else form.append('file', file!)
      if (srid !== undefined) form.append('srid', String(srid))
      if (charset) form.append('charset', charset)

      // The signal matters while the file itself is still going up: correcting the
      // encoding mid-transfer would otherwise leave the first upload running unwatched.
      const inspection = await api.postForm<Inspection>(
        `/api/projects/${projectId}/imports/inspect`,
        form,
        signal,
      )
      uploadId.current = inspection.uploadId
      return inspection
    },
    enabled: file !== null,
    placeholderData: (previous, previousQuery) => {
      // Keeps the preview standing while a changed CRS or encoding is re-inspected,
      // instead of blanking it out. Only for the same file though -- the previous file's
      // fields under a new filename would state something untrue.
      return previousQuery?.queryKey[3] === current ? previous : undefined
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
