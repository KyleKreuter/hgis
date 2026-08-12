import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { TriangleAlertIcon } from 'lucide-react'
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
import { Badge } from '@/components/ui/badge'
import { Progress, ProgressLabel, ProgressValue } from '@/components/ui/progress'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { ApiError } from '@/api/client'
import {
  isJobFinished,
  useImportJob,
  useInspection,
  useRefreshAfterImport,
  useStartImport,
  type Inspection,
  type InspectedField,
} from '@/api/imports'
import { GEOMETRY_LABELS } from './geometry'
import {
  CRS_CONFIDENCE_LABELS,
  formatCharset,
  formatFeatureCount,
  formatLocation,
  formatSample,
} from './inspection'

/**
 * Extensions SourceReaderFactory dispatches on. A bare .shp is deliberately absent:
 * a shapefile is a set of files (.dbf holds the attributes, .prj the CRS), so it has
 * to arrive zipped or it would import without attributes and without a CRS.
 */
const ACCEPTED = '.zip,.gpkg,.geojson,.json,.csv'

/** Mirrors spring.servlet.multipart.max-file-size -- checked on selection, before the
 *  inspection would transfer an oversized file just to have it rejected. */
const MAX_BYTES = 500 * 1024 * 1024

/**
 * Sample values shown per field. Ten arrive; three fit on one line and are enough to
 * recognise mangled umlauts, which is what the values are here for.
 */
const SAMPLES_SHOWN = 3

const AUTO = 'auto'

const CRS_OPTIONS = [
  { value: AUTO, label: 'Aus der Datei übernehmen' },
  { value: '25832', label: 'EPSG:25832 (UTM 32N)' },
  { value: '25833', label: 'EPSG:25833 (UTM 33N)' },
  { value: '4326', label: 'EPSG:4326 (WGS 84)' },
  { value: '31467', label: 'EPSG:31467 (Gauß-Krüger 3)' },
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

  const sridOverride = srid === AUTO ? undefined : Number(srid)
  const charsetOverride = charset === AUTO ? undefined : charset
  const tooLarge = file !== null && file.size > MAX_BYTES

  // Runs on selection and again after every override, so the user sees what the import
  // would produce -- with the file transferred once, not once per correction.
  const inspection = useInspection(projectId, tooLarge ? null : file, sridOverride, charsetOverride)

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
    if (!file || tooLarge) return
    setError(undefined)

    const options = { name, srid: sridOverride, charset: charsetOverride }

    try {
      // The inspection already carried the file over, so the import refers to it by id.
      // Without one -- the inspection failed, or the endpoint is not there -- the file
      // goes along as before: a preview that did not work must not block the import.
      const started = await startImport.mutateAsync(
        inspection.data
          ? { ...options, uploadId: inspection.data.uploadId }
          : { ...options, file },
      )
      setJobId(started.id)
    } catch (caught) {
      // The endpoint opens and inspects the file synchronously, so this is where an
      // unknown format, an unreadable file or an implausible CRS arrives -- with a
      // message worth showing verbatim, since it usually names the actual problem.
      setError(
        caught instanceof ApiError
          ? caught.message
          : 'Das Programm konnte den Import nicht starten.',
      )
    }
  }

  function handleOpenChange(next: boolean) {
    if (!next) {
      if (running) {
        toast.info('Der Import läuft im Hintergrund weiter')
      }
      reset()
    }
    onOpenChange(next)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      {/* The preview makes the dialog tall: on a short window it scrolls rather than
          running off the screen. */}
      <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-xl">
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
                    <p
                      className={cn(
                        'text-xs tabular-nums',
                        tooLarge ? 'text-destructive' : 'text-muted-foreground',
                      )}
                    >
                      {formatBytes(file.size)}
                      {tooLarge && '. Erlaubt sind höchstens 500 MB.'}
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
                    placeholder="Übernimmt den Dateinamen"
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
                  Beide Angaben sind nur nötig, wenn die Datei sie nicht mitbringt. CSV hat
                  nie ein Koordinatensystem, Shapefiles ohne <code>.prj</code> auch nicht. Die
                  Kodierung betrifft die Attributwerte von Shapefiles. Die Vorschau zeigt, was
                  dabei herauskommt.
                </p>

                {file && !tooLarge && <ImportPreview inspection={inspection} />}

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
                <Button
                  type="submit"
                  // Disabled while the inspection runs: its result decides whether the
                  // file still has to be sent, and a preview nobody has seen yet is no
                  // basis for writing anything.
                  disabled={
                    !file || tooLarge || startImport.isPending || running || inspection.isFetching
                  }
                >
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

/**
 * What the file contains, before a single row is written.
 *
 * The frame stays put while the content changes, so switching the encoding does not
 * make the dialog jump: the previous result keeps standing, dimmed, until the new one
 * arrives.
 */
function ImportPreview({ inspection }: { inspection: ReturnType<typeof useInspection> }) {
  return (
    <div className="grid gap-2 rounded-md border p-2.5">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium tracking-wide uppercase text-muted-foreground">
          Vorschau
        </span>
        {inspection.isFetching && (
          <span className="text-xs text-muted-foreground">wird geprüft…</span>
        )}
      </div>
      <PreviewBody inspection={inspection} />
    </div>
  )
}

function PreviewBody({ inspection }: { inspection: ReturnType<typeof useInspection> }) {
  if (inspection.isError) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Vorschau nicht möglich</AlertTitle>
        <AlertDescription>
          {inspection.error instanceof ApiError
            ? inspection.error.message
            : 'Das Programm konnte die Datei nicht lesen.'}
          <span className="block text-xs">
            Ein Import ist trotzdem möglich. Das Programm prüft die Datei dann erst beim
            Start.
          </span>
        </AlertDescription>
      </Alert>
    )
  }

  if (!inspection.data) {
    return <p className="text-xs text-muted-foreground">Datei wird übertragen und geprüft…</p>
  }

  return (
    <InspectionSummary inspection={inspection.data} outdated={inspection.isPlaceholderData} />
  )
}

function InspectionSummary({
  inspection,
  outdated,
}: {
  inspection: Inspection
  outdated: boolean
}) {
  const location = formatLocation(inspection.extentWgs84)

  return (
    <div className={cn('grid gap-2', outdated && 'opacity-50')}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
        <Badge variant="secondary">{GEOMETRY_LABELS[inspection.geometryType]}</Badge>
        <span className="tabular-nums">{formatFeatureCount(inspection.featureCount)}</span>
        <span className="text-muted-foreground">·</span>
        <span className="tabular-nums">
          EPSG:{inspection.srid}{' '}
          <span className="text-muted-foreground">
            ({CRS_CONFIDENCE_LABELS[inspection.crsConfidence]})
          </span>
        </span>
        <span className="text-muted-foreground">·</span>
        <span>{formatCharset(inspection.charset)}</span>
      </div>

      <p className="text-xs text-muted-foreground">
        {location
          ? `Daten liegen bei ${location}.`
          : 'Das Programm konnte die Lage der Daten nicht bestimmen.'}
      </p>

      {inspection.crsConfidence === 'GUESSED' && (
        <Alert variant="destructive">
          <TriangleAlertIcon />
          <AlertTitle>Koordinatensystem nur geraten</AlertTitle>
          <AlertDescription>
            Die Datei nennt kein Koordinatensystem. Das Programm hat EPSG:{inspection.srid}{' '}
            aus der Lage der Koordinaten abgeleitet. Wenn die Verortung oben nicht stimmt,
            korrigieren Sie sie hier. Sonst fällt der Fehler erst auf, wenn die Daten längst
            geschrieben sind.
          </AlertDescription>
        </Alert>
      )}

      {inspection.fields.length === 0 ? (
        <p className="text-xs text-muted-foreground">Die Datei hat keine Attributfelder.</p>
      ) : (
        <div className="max-h-40 overflow-y-auto rounded-md border">
          <Table>
            <TableHeader className="sticky top-0 z-10 bg-popover">
              <TableRow>
                <TableHead className="h-7 text-xs">Feld</TableHead>
                <TableHead className="h-7 text-xs">Typ</TableHead>
                <TableHead className="h-7 text-xs">Beispielwerte</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {inspection.fields.map((field) => (
                <FieldRow key={field.name} field={field} />
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}

function FieldRow({ field }: { field: InspectedField }) {
  return (
    <TableRow>
      <TableCell className="p-1.5 text-xs font-medium">{field.name}</TableCell>
      <TableCell className="p-1.5 text-xs text-muted-foreground">{field.dataType}</TableCell>
      <TableCell className="p-1.5 text-xs">
        <div className="flex items-center gap-1">
          {field.sampleValues.length === 0 ? (
            <span className="text-muted-foreground/50 italic">keine Werte</span>
          ) : (
            field.sampleValues.slice(0, SAMPLES_SHOWN).map((value, index) => {
              const sample = formatSample(value)
              return (
                <span
                  // Sample values repeat and may be null, so their position is the only key.
                  key={index}
                  className={cn(
                    'max-w-40 truncate',
                    sample.placeholder
                      ? 'text-muted-foreground/50 italic'
                      : 'rounded bg-muted px-1',
                  )}
                  title={sample.text}
                >
                  {sample.text}
                </span>
              )
            })
          )}
        </div>
      </TableCell>
    </TableRow>
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
          {job.message ?? 'Der Server hat keinen Grund genannt.'}
          <span className="block text-xs">
            Das Programm hat nichts geschrieben. Es bleibt keine halb gefüllte Tabelle
            zurück.
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
