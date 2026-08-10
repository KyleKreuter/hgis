import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Progress, ProgressLabel, ProgressValue } from '@/components/ui/progress'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ApiError } from '@/api/client'
import {
  isJobFinished,
  useImportJob,
  useRefreshAfterImport,
  useStartImport,
} from '@/api/imports'

/**
 * Extensions SourceReaderFactory dispatches on. A bare .shp is deliberately absent:
 * a shapefile is a set of files (.dbf holds the attributes, .prj the CRS), so it has
 * to arrive zipped or it would import without attributes and without a CRS.
 */
const ACCEPTED = '.zip,.gpkg,.geojson,.json,.csv'

/** Mirrors spring.servlet.multipart.max-file-size -- checked here so a 500 MB upload
 *  is not transferred just to be rejected at the end. */
const MAX_BYTES = 500 * 1024 * 1024

const AUTO = 'auto'

const CRS_OPTIONS = [
  { value: AUTO, label: 'Aus der Datei übernehmen' },
  { value: '25832', label: 'EPSG:25832 — UTM 32N' },
  { value: '25833', label: 'EPSG:25833 — UTM 33N' },
  { value: '4326', label: 'EPSG:4326 — WGS 84' },
  { value: '31467', label: 'EPSG:31467 — Gauß-Krüger 3' },
]

const CHARSET_OPTIONS = [
  { value: AUTO, label: 'Automatisch erkennen' },
  { value: 'UTF-8', label: 'UTF-8' },
  { value: 'windows-1252', label: 'Windows-1252' },
  { value: 'ISO-8859-1', label: 'ISO-8859-1' },
]

function labelOf(options: { value: string; label: string }[], value: string): string {
  return options.find((option) => option.value === value)?.label ?? value
}

/** "gebaeude_hamburg.zip" -> "gebaeude_hamburg", matching UploadStorage.baseNameOf. */
function baseName(filename: string): string {
  const withoutPath = filename.replace(/^.*[\\/]/, '')
  const dot = withoutPath.lastIndexOf('.')
  return dot > 0 ? withoutPath.slice(0, dot) : withoutPath
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

interface ImportDialogProps {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function ImportDialog({ projectId, open, onOpenChange }: ImportDialogProps) {
  const startImport = useStartImport(projectId)
  const refreshAfterImport = useRefreshAfterImport(projectId)

  const [file, setFile] = useState<File | null>(null)
  const [name, setName] = useState('')
  const [srid, setSrid] = useState(AUTO)
  const [charset, setCharset] = useState(AUTO)
  const [error, setError] = useState<string>()
  const [jobId, setJobId] = useState<string | null>(null)
  // The name field is only auto-filled from the file until the user types their own.
  const nameTouched = useRef(false)

  const { data: job } = useImportJob(jobId)
  const finished = isJobFinished(job?.status)
  const running = jobId !== null && !finished

  // The layer only exists once the job succeeded, so the caches cannot be refreshed
  // when the upload is accepted -- it has to happen on the transition to SUCCEEDED.
  useEffect(() => {
    if (job?.status === 'SUCCEEDED') refreshAfterImport()
    // refreshAfterImport is recreated on every render; the job status is what matters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job?.status])

  function reset() {
    setFile(null)
    setName('')
    setSrid(AUTO)
    setCharset(AUTO)
    setError(undefined)
    setJobId(null)
    nameTouched.current = false
  }

  function handleFile(selected: File | null) {
    setError(undefined)
    setFile(selected)
    if (selected && !nameTouched.current) setName(baseName(selected.name))
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!file) return
    setError(undefined)

    if (file.size > MAX_BYTES) {
      setError(`Die Datei ist ${formatBytes(file.size)} groß, erlaubt sind 500 MB.`)
      return
    }

    try {
      const started = await startImport.mutateAsync({
        file,
        name,
        srid: srid === AUTO ? undefined : Number(srid),
        charset: charset === AUTO ? undefined : charset,
      })
      setJobId(started.id)
    } catch (caught) {
      // The endpoint opens and inspects the file synchronously, so this is where an
      // unknown format, an unreadable file or an implausible CRS arrives -- with a
      // message worth showing verbatim, since it usually names the actual problem.
      setError(
        caught instanceof ApiError ? caught.message : 'Der Import konnte nicht gestartet werden.',
      )
    }
  }

  function handleOpenChange(next: boolean) {
    if (!next) {
      if (running) {
        toast.info('Der Import läuft im Hintergrund weiter.')
      }
      reset()
    }
    onOpenChange(next)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Daten importieren</DialogTitle>
            <DialogDescription>
              Shapefile (als ZIP), GeoPackage, GeoJSON oder CSV. Jede Datei wird zu einem
              eigenen Layer.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 py-4">
            {jobId ? (
              <JobProgress job={job} />
            ) : (
              <>
                <div className="grid gap-1.5">
                  <Label htmlFor="import-file">Datei</Label>
                  <Input
                    id="import-file"
                    type="file"
                    accept={ACCEPTED}
                    onChange={(e) => handleFile(e.target.files?.[0] ?? null)}
                    className="file:mr-3 file:text-xs file:text-muted-foreground"
                  />
                  {file && (
                    <p className="text-xs text-muted-foreground tabular-nums">
                      {formatBytes(file.size)}
                    </p>
                  )}
                </div>

                <div className="grid gap-1.5">
                  <Label htmlFor="import-name">Layername</Label>
                  <Input
                    id="import-name"
                    value={name}
                    onChange={(e) => {
                      nameTouched.current = true
                      setName(e.target.value)
                    }}
                    placeholder="Wird aus dem Dateinamen übernommen"
                  />
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="grid gap-1.5">
                    <Label htmlFor="import-crs">Koordinatensystem</Label>
                    <Select value={srid} onValueChange={(value) => value && setSrid(value)}>
                      <SelectTrigger id="import-crs" className="w-full">
                        <SelectValue>{(value: string) => labelOf(CRS_OPTIONS, value)}</SelectValue>
                      </SelectTrigger>
                      <SelectContent>
                        {CRS_OPTIONS.map((option) => (
                          <SelectItem key={option.value} value={option.value}>
                            {option.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="grid gap-1.5">
                    <Label htmlFor="import-charset">Zeichenkodierung</Label>
                    <Select
                      value={charset}
                      onValueChange={(value) => value && setCharset(value)}
                    >
                      <SelectTrigger id="import-charset" className="w-full">
                        <SelectValue>
                          {(value: string) => labelOf(CHARSET_OPTIONS, value)}
                        </SelectValue>
                      </SelectTrigger>
                      <SelectContent>
                        {CHARSET_OPTIONS.map((option) => (
                          <SelectItem key={option.value} value={option.value}>
                            {option.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                </div>

                <p className="text-xs text-muted-foreground">
                  Beide Angaben sind nur nötig, wenn die Datei sie nicht mitbringt: CSV
                  führt nie ein Koordinatensystem, Shapefiles ohne <code>.prj</code> auch
                  nicht. Die Kodierung betrifft die Attributwerte von Shapefiles.
                </p>

                {error && (
                  <Alert variant="destructive">
                    <AlertTitle>Import nicht gestartet</AlertTitle>
                    <AlertDescription>{error}</AlertDescription>
                  </Alert>
                )}
              </>
            )}
          </div>

          <DialogFooter>
            {finished ? (
              <Button type="button" onClick={() => handleOpenChange(false)}>
                Schließen
              </Button>
            ) : (
              <>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => handleOpenChange(false)}
                >
                  {running ? 'Im Hintergrund fortsetzen' : 'Abbrechen'}
                </Button>
                <Button type="submit" disabled={!file || startImport.isPending || running}>
                  {startImport.isPending ? 'Wird übertragen…' : 'Importieren'}
                </Button>
              </>
            )}
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function JobProgress({ job }: { job: ReturnType<typeof useImportJob>['data'] }) {
  if (!job) {
    return <p className="text-sm text-muted-foreground">Import wird vorbereitet…</p>
  }

  if (job.status === 'FAILED') {
    return (
      <Alert variant="destructive">
        <AlertTitle>Import fehlgeschlagen</AlertTitle>
        <AlertDescription>
          {job.message ?? 'Der Grund wurde nicht übermittelt.'}
          <span className="block text-xs">
            Es wurde nichts geschrieben — eine halb gefüllte Tabelle bleibt nicht zurück.
          </span>
        </AlertDescription>
      </Alert>
    )
  }

  if (job.status === 'SUCCEEDED') {
    return (
      <Alert>
        <AlertTitle>Import abgeschlossen</AlertTitle>
        <AlertDescription>
          <span className="tabular-nums">{job.processedCount.toLocaleString('de-DE')}</span>{' '}
          Objekte geschrieben.
          {job.skippedCount > 0 && (
            <span className="block">
              {job.skippedCount.toLocaleString('de-DE')} Objekte übersprungen, weil ihre
              Geometrie nicht in die Layertabelle passte.
            </span>
          )}
        </AlertDescription>
      </Alert>
    )
  }

  const percent =
    job.totalCount && job.totalCount > 0
      ? Math.min(100, Math.round((job.processedCount / job.totalCount) * 100))
      : null

  return (
    <div className="grid gap-2">
      {percent === null ? (
        // Without a total from the source there is nothing honest to fill a bar with,
        // so the running count is shown instead of a bar that only pretends to know.
        <p className="text-sm tabular-nums">
          {job.processedCount.toLocaleString('de-DE')} Objekte geschrieben…
        </p>
      ) : (
        <Progress value={percent}>
          <ProgressLabel className="text-sm font-normal">Objekte werden geschrieben</ProgressLabel>
          {/* Base UI passes its own formatted percentage in; the raw counts are more
              use here, so the render function ignores the argument. */}
          <ProgressValue>
            {() =>
              `${job.processedCount.toLocaleString('de-DE')} / ${job.totalCount?.toLocaleString('de-DE')}`
            }
          </ProgressValue>
        </Progress>
      )}
      {job.filename && <p className="text-xs text-muted-foreground">{job.filename}</p>}
    </div>
  )
}
