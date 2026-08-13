import { useEffect, useMemo, useRef, useState } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'
import { toast } from 'sonner'
import {
  ExternalLink,
  Globe,
  Image as ImageIcon,
  RefreshCw,
  Search,
  TriangleAlert,
} from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
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
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { formatCount } from '@/lib/format'
import { ApiError } from '@/api/client'
import { isJobFinished, useJob, useRefreshAfterImport, type Job } from '@/api/imports'
import {
  useGeoportalCatalog,
  useGeoportalCount,
  useGeoportalDataset,
  useRefreshGeoportalCatalog,
  useStartGeoportalImport,
  type GeoportalCollection,
  type GeoportalDatasetKind,
  type GeoportalDatasetSummary,
  type GeoportalField,
} from '@/api/geoportal'
import { formatFeatureCount } from '@/layers/inspection'
import { useMapViewport } from '@/map/mapViewportStore'
import {
  activeDatasetId,
  isServiceEntry,
  needsCollectionChoice,
  visibleCollections,
} from './collections'
import { formatFieldValues, isImportable } from './fields'
import { buildGeoportalImportBody } from './importBody'
import {
  defaultGeoportalFilters,
  distinctAgencies,
  distinctTopics,
  filterDatasets,
  sortByTitle,
  type DatasetKindFilter,
  type GeoportalFilters,
} from './search'
import { estimateImportDuration, exceedsWarningThreshold, formatDurationEstimate } from './duration'

/** Not real topic/agency values, so they can never collide with one from the catalog. */
const ALL_TOPICS = '__alle-themen__'
const ALL_AGENCIES = '__alle-behoerden__'

const KIND_ICONS: Record<GeoportalDatasetKind, typeof Globe> = {
  FEATURES: Globe,
  BOTH: Globe,
  WMS: ImageIcon,
}

const KIND_TITLES: Record<GeoportalDatasetKind, string> = {
  FEATURES: 'Objekte',
  BOTH: 'Objekte und Kartenbild',
  WMS: 'Nur Kartenbild',
}

/** Row height in pixels. Must match the class on the row, or the virtualiser drifts. */
const ROW_HEIGHT = 44

interface GeoportalDialogProps {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Finds a dataset in Hamburg's Geoportal and imports it, without the detour of
 * downloading a file first (CONTRACT.md phase 23, plan 6.5). Wider than `ImportDialog`
 * on purpose -- a catalog of several hundred entries needs a list and a detail pane side
 * by side, not a single stacked form.
 */
export function GeoportalDialog({ projectId, open, onOpenChange }: GeoportalDialogProps) {
  const catalog = useGeoportalCatalog()
  const refreshCatalog = useRefreshGeoportalCatalog()
  const startImport = useStartGeoportalImport(projectId)
  const refreshAfterImport = useRefreshAfterImport(projectId)
  const viewportBbox = useMapViewport((state) => state.bbox)

  const [filters, setFilters] = useState<GeoportalFilters>(defaultGeoportalFilters)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  // Only ever set for a service entry (CONTRACT.md 11.9). A flat entry's own id already
  // names a collection, so there is nothing left to choose.
  const [collectionId, setCollectionId] = useState<string | null>(null)
  const [collectionQuery, setCollectionQuery] = useState('')
  const [name, setName] = useState('')
  const [useMapExtent, setUseMapExtent] = useState(false)
  const [selectFieldsEnabled, setSelectFieldsEnabled] = useState(false)
  const [selectedFields, setSelectedFields] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string>()
  const [jobId, setJobId] = useState<string | null>(null)

  // Two detail calls, because a service entry needs both: its own detail carries the
  // collection list, and the chosen collection's carries the fields and the object
  // count. Keeping the first one mounted is what lets the user pick a different
  // collection afterwards without a second round trip.
  const entryDetail = useGeoportalDataset(selectedId)
  const collectionDetail = useGeoportalDataset(collectionId)
  const detail = collectionId === null ? entryDetail : collectionDetail
  const datasetId = activeDatasetId(selectedId, collectionId)
  const count = useGeoportalCount(datasetId, useMapExtent ? viewportBbox : null)

  const { data: job } = useJob(jobId)
  const finished = isJobFinished(job?.status)
  const running = jobId !== null && !finished

  // The layer only exists once the job succeeded -- same rule ImportDialog follows.
  useEffect(() => {
    if (job?.status === 'SUCCEEDED') refreshAfterImport()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job?.status])

  // All fields pre-checked the moment a dataset's own field list arrives (decision E2).
  useEffect(() => {
    if (detail.data) setSelectedFields(new Set(detail.data.fields.map((field) => field.name)))
  }, [detail.data])

  const datasets = useMemo(() => catalog.data?.datasets ?? [], [catalog.data])
  const filteredDatasets = useMemo(() => sortByTitle(filterDatasets(datasets, filters)), [datasets, filters])
  const topics = useMemo(() => distinctTopics(datasets), [datasets])
  const agencies = useMemo(() => distinctAgencies(datasets), [datasets])

  const summary = datasets.find((dataset) => dataset.id === selectedId) ?? null
  const wmsOnly = summary !== null && !isImportable(summary.kind)
  const needsCollection = needsCollectionChoice(summary, collectionId)
  // Nothing to count while the entry stands for a whole service: its own detail carries
  // no `featureCount`, and the summary's belongs to no collection either (11.9).
  const effectiveFeatureCount = needsCollection
    ? null
    : useMapExtent && count.data
      ? count.data.featureCount
      : (detail.data ?? summary)?.featureCount ?? null
  const exceedsWarning = exceedsWarningThreshold(effectiveFeatureCount)
  const duration = effectiveFeatureCount !== null ? estimateImportDuration(effectiveFeatureCount) : null

  function reset() {
    setFilters(defaultGeoportalFilters())
    setSelectedId(null)
    clearCollection()
    setName('')
    setUseMapExtent(false)
    setSelectFieldsEnabled(false)
    setSelectedFields(new Set())
    setError(undefined)
    setJobId(null)
  }

  /** Back to the choice itself -- both halves of it, or the search would outlive the list. */
  function clearCollection() {
    setCollectionId(null)
    setCollectionQuery('')
  }

  function handleSelect(id: string) {
    setSelectedId(id)
    clearCollection()
    setName('')
    setUseMapExtent(false)
    setSelectFieldsEnabled(false)
    setError(undefined)
  }

  /**
   * A different collection is a different dataset: the layer name and the map-extent
   * count were chosen for the previous one and would otherwise carry over silently.
   */
  function handleChooseCollection(id: string) {
    setCollectionId(id)
    setName('')
    setUseMapExtent(false)
    setSelectFieldsEnabled(false)
    setError(undefined)
  }

  function toggleField(fieldName: string) {
    setSelectedFields((current) => {
      const next = new Set(current)
      if (next.has(fieldName)) next.delete(fieldName)
      else next.add(fieldName)
      return next
    })
  }

  async function handleImport() {
    // `datasetId` rather than `detail.data.id`: for a service entry the two differ, and
    // a service id alone is a `400` on the import endpoint (CONTRACT.md 11.9).
    if (!detail.data || datasetId === null || needsCollection) return
    setError(undefined)

    const allFieldNames = detail.data.fields.map((field) => field.name)
    try {
      const started = await startImport.mutateAsync(
        buildGeoportalImportBody({
          datasetId,
          name,
          allFieldNames,
          // The checkboxes only matter once the toggle reveals them -- with it off,
          // every field goes along regardless of what an earlier session left checked.
          selectedFields: selectFieldsEnabled ? selectedFields : new Set(allFieldNames),
          useMapExtent,
          mapBbox: viewportBbox,
        }),
      )
      setJobId(started.id)
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : 'Das Programm konnte den Import nicht starten.',
      )
    }
  }

  function handleOpenChange(next: boolean) {
    if (!next) {
      if (running) toast.info('Der Import läuft im Hintergrund weiter')
      reset()
    }
    onOpenChange(next)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="flex h-[min(85dvh,44rem)] max-h-[calc(100dvh-2rem)] flex-col gap-3 overflow-hidden sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle>Daten aus dem Geoportal Hamburg</DialogTitle>
          <DialogDescription>
            Objektdaten aus Hamburgs offenem Geoportal, ohne den Umweg über eine Datei.
          </DialogDescription>
        </DialogHeader>

        {jobId ? (
          <div className="flex-1 overflow-y-auto py-2">
            <JobProgress job={job} />
          </div>
        ) : (
          <>
            <CatalogToolbar
              filters={filters}
              onFiltersChange={setFilters}
              topics={topics}
              agencies={agencies}
              resultCount={filteredDatasets.length}
              onRefresh={() =>
                refreshCatalog.mutate(undefined, {
                  onError: () => toast.error('Das Programm konnte den Katalog nicht neu laden'),
                })
              }
              refreshing={refreshCatalog.isPending}
            />

            <div className="flex min-h-0 flex-1 gap-3">
              <DatasetList
                loading={catalog.isPending}
                error={catalog.isError}
                datasets={filteredDatasets}
                selectedId={selectedId}
                onSelect={handleSelect}
              />
              <DatasetDetailPane
                summary={summary}
                wmsOnly={wmsOnly}
                detail={detail}
                collections={entryDetail.data?.collections ?? []}
                needsCollection={needsCollection}
                collectionId={collectionId}
                collectionQuery={collectionQuery}
                onCollectionQueryChange={setCollectionQuery}
                onChooseCollection={handleChooseCollection}
                onClearCollection={clearCollection}
                name={name}
                onNameChange={setName}
                useMapExtent={useMapExtent}
                onUseMapExtentChange={setUseMapExtent}
                hasViewport={viewportBbox !== null}
                countFetching={count.isFetching}
                selectFieldsEnabled={selectFieldsEnabled}
                onSelectFieldsEnabledChange={setSelectFieldsEnabled}
                selectedFields={selectedFields}
                onToggleField={toggleField}
                effectiveFeatureCount={effectiveFeatureCount}
                exceedsWarning={exceedsWarning}
                duration={duration}
              />
            </div>

            {error && (
              <Alert variant="destructive">
                <AlertTitle>Import nicht gestartet</AlertTitle>
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
          </>
        )}

        <DialogFooter>
          {finished ? (
            <Button type="button" onClick={() => handleOpenChange(false)}>
              Schließen
            </Button>
          ) : jobId ? (
            <Button type="button" variant="outline" onClick={() => handleOpenChange(false)}>
              Im Hintergrund fortsetzen
            </Button>
          ) : (
            <>
              <Button type="button" variant="outline" onClick={() => handleOpenChange(false)}>
                Abbrechen
              </Button>
              <Button
                type="button"
                onClick={handleImport}
                disabled={
                  !summary || wmsOnly || needsCollection || !detail.data || startImport.isPending
                }
              >
                {startImport.isPending ? 'Wird gestartet…' : 'Importieren'}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CatalogToolbar({
  filters,
  onFiltersChange,
  topics,
  agencies,
  resultCount,
  onRefresh,
  refreshing,
}: {
  filters: GeoportalFilters
  onFiltersChange: (filters: GeoportalFilters) => void
  topics: string[]
  agencies: string[]
  resultCount: number
  onRefresh: () => void
  refreshing: boolean
}) {
  function toggleKind(kind: DatasetKindFilter) {
    const next = new Set(filters.kinds)
    if (next.has(kind)) next.delete(kind)
    else next.add(kind)
    onFiltersChange({ ...filters, kinds: next })
  }

  return (
    <div className="grid gap-2">
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
          {/* Focus lands here on open (plan 6.5, Schritt 2) -- the search is the fastest
              way into a catalog of about 1100 entries. The placeholder promises only
              name and agency: the service directory carries no description for any
              catalog entry, so naming a field that is always empty would be misleading.
              `matchesQuery` (search.ts) still checks description too -- harmless while
              it stays empty, and free the moment the backend can fill it in for the
              detail view. */}
          <Input
            autoFocus
            value={filters.query}
            onChange={(event) => onFiltersChange({ ...filters, query: event.target.value })}
            placeholder="Name oder Behörde durchsuchen"
            aria-label="Geoportal-Katalog durchsuchen"
            className="pl-8"
          />
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onRefresh}
          disabled={refreshing}
          title="Katalog neu vom Geoportal laden (Nutzerentscheidung E5: nur auf Knopfdruck)"
        >
          <RefreshCw className={cn('size-3.5', refreshing && 'animate-spin')} />
          Katalog aktualisieren
        </Button>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <span className="text-xs tabular-nums text-muted-foreground">
          {formatCount(resultCount)} {resultCount === 1 ? 'Datensatz' : 'Datensätze'}
        </span>

        <div className="ml-auto flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-1.5 text-xs">
            <Checkbox checked={filters.kinds.has('features')} onCheckedChange={() => toggleKind('features')} />
            Objekte
          </label>
          <label className="flex items-center gap-1.5 text-xs">
            <Checkbox checked={filters.kinds.has('imageOnly')} onCheckedChange={() => toggleKind('imageOnly')} />
            Nur Kartenbild
          </label>

          <Select
            value={filters.topic ?? ALL_TOPICS}
            onValueChange={(value) => value && onFiltersChange({ ...filters, topic: value === ALL_TOPICS ? null : value })}
          >
            <SelectTrigger size="sm" className="w-40">
              <SelectValue>{(value: string) => (value === ALL_TOPICS ? 'Alle Themen' : value)}</SelectValue>
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_TOPICS}>Alle Themen</SelectItem>
              {topics.map((topic) => (
                <SelectItem key={topic} value={topic}>
                  {topic}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select
            value={filters.agency ?? ALL_AGENCIES}
            onValueChange={(value) => value && onFiltersChange({ ...filters, agency: value === ALL_AGENCIES ? null : value })}
          >
            <SelectTrigger size="sm" className="w-40">
              <SelectValue>{(value: string) => (value === ALL_AGENCIES ? 'Alle Behörden' : value)}</SelectValue>
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_AGENCIES}>Alle Behörden</SelectItem>
              {agencies.map((agency) => (
                <SelectItem key={agency} value={agency}>
                  {agency}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>
    </div>
  )
}

function DatasetList({
  loading,
  error,
  datasets,
  selectedId,
  onSelect,
}: {
  loading: boolean
  error: boolean
  datasets: GeoportalDatasetSummary[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  // A plain scroller rather than ScrollArea: the virtualiser needs the scrolling element
  // itself, and ScrollArea keeps its viewport to itself. Same arrangement the attribute
  // table uses for the same reason.
  const scrollRef = useRef<HTMLDivElement>(null)
  const virtualizer = useVirtualizer({
    count: datasets.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 8,
  })

  // A changed filter is a different list under the same scroll offset -- staying put
  // would leave the user looking at entry 700 of a search that just started over.
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 })
  }, [datasets])

  return (
    <div ref={scrollRef} className="w-2/5 min-w-0 overflow-y-auto rounded-md border p-1">
      {loading && <p className="p-2 text-xs text-muted-foreground">Katalog wird geladen…</p>}
      {error && (
        <Alert variant="destructive" className="m-1">
          <AlertTitle>Katalog nicht verfügbar</AlertTitle>
          <AlertDescription>Das Programm konnte den Geoportal-Katalog nicht laden.</AlertDescription>
        </Alert>
      )}
      {!loading && !error && datasets.length === 0 && (
        <p className="p-2 text-xs text-muted-foreground">Kein Datensatz gefunden.</p>
      )}
      {/*
       * Only the rows in view exist in the DOM. Measured before it was written, in a
       * production build on fast hardware: with all 1100 entries rendered, a keystroke
       * that widens the list back to the whole catalog cost 77 ms -- five frames, on
       * every press of the backspace key. Of that, 10 ms was the filtering and sorting
       * itself; the rest was building and laying out 1100 rows nobody can see at once.
       * Rendering a window of them instead brings the same keystroke to 3 ms.
       */}
      <ul
        aria-label="Datensätze im Geoportal-Katalog"
        className="relative"
        style={{ height: virtualizer.getTotalSize() }}
      >
        {virtualizer.getVirtualItems().map((row) => {
          const dataset = datasets[row.index]
          return (
            <DatasetRow
              key={dataset.id}
              dataset={dataset}
              selected={dataset.id === selectedId}
              top={row.start}
              position={row.index + 1}
              total={datasets.length}
              onSelect={() => onSelect(dataset.id)}
            />
          )
        })}
      </ul>
    </div>
  )
}

function DatasetRow({
  dataset,
  selected,
  top,
  position,
  total,
  onSelect,
}: {
  dataset: GeoportalDatasetSummary
  selected: boolean
  top: number
  position: number
  total: number
  onSelect: () => void
}) {
  const Icon = KIND_ICONS[dataset.kind]
  const service = isServiceEntry(dataset)
  return (
    // `aria-setsize`/`aria-posinset` because the DOM now holds a window, not the list:
    // without them a screen reader announces "1 of 20" for a catalog of 1100.
    <li
      className="absolute inset-x-0"
      style={{ top, height: ROW_HEIGHT }}
      aria-setsize={total}
      aria-posinset={position}
    >
      {/* Two lines, not one row of columns: the name is what the user searches by and
          has to win the space, and an agency like "Landesbetrieb Geoinformation und
          Vermessung" claims a whole row's width on its own -- sharing a line with it
          left names like "ALK…" unreadable. The object count moved to the detail pane;
          the catalog itself carries no count for most entries (the service directory
          does not report one), so a column that reads "—" almost everywhere told the
          user nothing. */}
      <button
        type="button"
        onClick={onSelect}
        className={cn(
          'flex size-full items-center gap-2 overflow-hidden rounded px-2 text-left text-xs',
          selected ? 'bg-accent' : 'hover:bg-accent/50',
        )}
      >
        <span title={KIND_TITLES[dataset.kind]} className="shrink-0">
          <Icon className="size-3.5 text-muted-foreground" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-1.5">
            <span className="truncate font-medium">{dataset.title}</span>
            {/* Says before the click that this entry is a service and that a choice
                follows. The count is the point: 247 collections and 2 are a different
                promise, and neither entry carries an object count to show instead. */}
            {service && (
              <Badge variant="secondary" className="shrink-0 font-normal">
                {formatCount(dataset.collectionCount)} Sammlungen
              </Badge>
            )}
          </span>
          {dataset.agency && <span className="block truncate text-muted-foreground">{dataset.agency}</span>}
        </span>
      </button>
    </li>
  )
}

interface DatasetDetailPaneProps {
  summary: GeoportalDatasetSummary | null
  wmsOnly: boolean
  detail: ReturnType<typeof useGeoportalDataset>
  /** The service's collections, empty for a flat entry (CONTRACT.md 11.9). */
  collections: GeoportalCollection[]
  /** True while a service entry is selected and no collection is chosen yet. */
  needsCollection: boolean
  collectionId: string | null
  collectionQuery: string
  onCollectionQueryChange: (query: string) => void
  onChooseCollection: (id: string) => void
  onClearCollection: () => void
  name: string
  onNameChange: (name: string) => void
  useMapExtent: boolean
  onUseMapExtentChange: (value: boolean) => void
  hasViewport: boolean
  countFetching: boolean
  selectFieldsEnabled: boolean
  onSelectFieldsEnabledChange: (value: boolean) => void
  selectedFields: Set<string>
  onToggleField: (name: string) => void
  effectiveFeatureCount: number | null
  exceedsWarning: boolean
  duration: ReturnType<typeof estimateImportDuration> | null
}

function DatasetDetailPane({
  summary,
  wmsOnly,
  detail,
  collections,
  needsCollection,
  collectionId,
  collectionQuery,
  onCollectionQueryChange,
  onChooseCollection,
  onClearCollection,
  name,
  onNameChange,
  useMapExtent,
  onUseMapExtentChange,
  hasViewport,
  countFetching,
  selectFieldsEnabled,
  onSelectFieldsEnabledChange,
  selectedFields,
  onToggleField,
  effectiveFeatureCount,
  exceedsWarning,
  duration,
}: DatasetDetailPaneProps) {
  if (!summary) {
    return (
      <div className="flex flex-1 items-center justify-center rounded-md border text-xs text-muted-foreground">
        Wählen Sie links einen Datensatz.
      </div>
    )
  }

  return (
    <ScrollArea className="flex-1 rounded-md border">
      <div className="grid gap-3 p-3">
        <div>
          <h3 className="text-sm font-medium">{summary.title}</h3>
          {/*
           * Detail first, list entry second: the service directory carries no
           * description at all, so a list entry's is always null. The detail fetches
           * one from the API landing page, which is the only place it exists.
           */}
          {(detail.data?.description ?? summary.description) && (
            <p className="mt-0.5 whitespace-pre-line text-xs text-muted-foreground">
              {detail.data?.description ?? summary.description}
            </p>
          )}
        </div>

        <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
          {summary.agency && (
            <span>
              <span className="text-muted-foreground">Behörde </span>
              {summary.agency}
            </span>
          )}
          {summary.topic && (
            <span>
              <span className="text-muted-foreground">Thema </span>
              {summary.topic}
            </span>
          )}
          {/* No count line for a service: "Anzahl unbekannt" would claim the number is
              missing, when in truth no collection is chosen to count yet (11.9). */}
          {!needsCollection && (
            <span className="tabular-nums">{formatFeatureCount(effectiveFeatureCount)}</span>
          )}
        </div>

        {wmsOnly && (
          <Alert>
            <ImageIcon />
            <AlertTitle>Nur als Kartenbild verfügbar</AlertTitle>
            <AlertDescription>
              Dieser Datensatz liefert keine Objekte, nur ein fertiges Kartenbild. Er lässt
              sich erst mit dem Bildweg (Stufe 2) als Hintergrundkarte nutzen – ein Import
              ist hier noch nicht möglich.
            </AlertDescription>
          </Alert>
        )}

        {detail.isPending && <p className="text-xs text-muted-foreground">Einzelheiten werden geladen…</p>}
        {detail.isError && (
          <Alert variant="destructive">
            <AlertTitle>Einzelheiten nicht verfügbar</AlertTitle>
            <AlertDescription>
              {detail.error instanceof ApiError
                ? detail.error.message
                : 'Das Programm konnte den Datensatz nicht laden.'}
            </AlertDescription>
          </Alert>
        )}

        {/* The choice this entry still owes, before anything that describes one
            collection. Nothing below says a word about fields or objects until it is
            made -- the detail carries none while no collection is chosen (11.9). Not
            for a service that only serves a map image: there is nothing to import from
            any of its collections, so there is nothing to choose between. */}
        {needsCollection && !wmsOnly && detail.data && (
          <CollectionPicker
            collections={collections}
            query={collectionQuery}
            onQueryChange={onCollectionQueryChange}
            onChoose={onChooseCollection}
          />
        )}

        {collectionId !== null && (
          <ChosenCollection
            title={
              collections.find((collection) => collection.id === collectionId)?.title ??
              detail.data?.title ??
              collectionId
            }
            onClear={onClearCollection}
          />
        )}

        {!needsCollection && detail.data && !wmsOnly && (
          <div className="grid gap-1.5">
            <Label htmlFor="geoportal-name">Layername</Label>
            <Input
              id="geoportal-name"
              value={name}
              onChange={(event) => onNameChange(event.target.value)}
              placeholder={detail.data.title}
            />
          </div>
        )}

        {!needsCollection && detail.data && (
          <div className="grid gap-1.5">
            <span className="text-xs font-medium tracking-wide uppercase text-muted-foreground">Felder</span>
            <FieldsTable
              fields={detail.data.fields}
              selectionEnabled={selectFieldsEnabled}
              selectedFields={selectedFields}
              onToggleField={onToggleField}
            />
          </div>
        )}

        {detail.data && (
          <div className="grid gap-1 text-xs">
            {/* Only where the service directory names an agency: some datasets leave it
                blank, and the caption on its own reads as a value that failed to load
                rather than one that does not exist (CONTRACT.md 11.7). The licence below
                applies either way, so only this line goes. */}
            {detail.data.attribution && (
              <p>
                <span className="text-muted-foreground">Quellenvermerk </span>
                {detail.data.attribution}
              </p>
            )}
            <p>
              <a
                href={detail.data.licenseUrl}
                target="_blank"
                rel="noreferrer"
                className="underline underline-offset-2 hover:text-foreground"
              >
                {detail.data.licenseName}
              </a>
            </p>
            <p className="flex flex-wrap gap-x-3 gap-y-1">
              {detail.data.metadataUrl && (
                <a
                  href={detail.data.metadataUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 underline underline-offset-2 hover:text-foreground"
                >
                  Metadatensatz <ExternalLink className="size-3" />
                </a>
              )}
              {detail.data.datasetUri && (
                <a
                  href={detail.data.datasetUri}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 underline underline-offset-2 hover:text-foreground"
                >
                  Datensatz im Geoportal Hamburg <ExternalLink className="size-3" />
                </a>
              )}
            </p>
          </div>
        )}

        {!needsCollection && !wmsOnly && detail.data && (
          <div className="grid gap-2 border-t pt-3">
            <label className="flex items-start gap-2 text-xs">
              <Checkbox
                checked={useMapExtent}
                disabled={!hasViewport}
                onCheckedChange={(checked) => onUseMapExtentChange(checked === true)}
              />
              <span>
                Nur den aktuellen Kartenausschnitt
                {!hasViewport && <span className="block text-muted-foreground">Karte noch nicht bereit</span>}
                {useMapExtent && countFetching && (
                  <span className="block text-muted-foreground">Objektzahl wird ermittelt…</span>
                )}
              </span>
            </label>

            <label className="flex items-start gap-2 text-xs">
              <Checkbox
                checked={selectFieldsEnabled}
                onCheckedChange={(checked) => onSelectFieldsEnabledChange(checked === true)}
              />
              Felder auswählen
            </label>

            {exceedsWarning && duration && (
              <Alert>
                <TriangleAlert />
                <AlertTitle>Große Datenmenge</AlertTitle>
                <AlertDescription>
                  {formatFeatureCount(effectiveFeatureCount)}, das dauert {formatDurationEstimate(duration)}.
                  {!useMapExtent &&
                    ' Der aktuelle Kartenausschnitt würde das verkürzen.'}
                  {' '}
                  Es gibt keine feste Obergrenze – Sie entscheiden, ob Sie fortfahren.
                </AlertDescription>
              </Alert>
            )}
          </div>
        )}
      </div>
    </ScrollArea>
  )
}

/**
 * The collection choice for a service entry (CONTRACT.md 11.9).
 *
 * With a search field, not without: `xplan` alone holds 247 collections, and a list of
 * that length with no way to narrow it is a list nobody reads to the end. Not
 * virtualised, unlike the catalog beside it -- a keystroke that shows all 247 again was
 * measured at 4 ms, well inside a frame. The measurement is what decides that, not the
 * row count.
 */
function CollectionPicker({
  collections,
  query,
  onQueryChange,
  onChoose,
}: {
  collections: GeoportalCollection[]
  query: string
  onQueryChange: (query: string) => void
  onChoose: (id: string) => void
}) {
  const visible = useMemo(() => visibleCollections(collections, query), [collections, query])

  if (collections.length === 0) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Keine Sammlung erhalten</AlertTitle>
        <AlertDescription>Der Dienst hat keine Sammlung gemeldet.</AlertDescription>
      </Alert>
    )
  }

  return (
    <div className="grid gap-2 border-t pt-3">
      <div className="grid gap-0.5">
        <span className="text-xs font-medium tracking-wide uppercase text-muted-foreground">
          Sammlung wählen
        </span>
        <p className="text-xs text-muted-foreground">
          Dieser Dienst enthält {formatCount(collections.length)} Sammlungen. Wählen Sie eine
          Sammlung. Danach können Sie die Sammlung importieren.
        </p>
      </div>

      <div className="relative">
        <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          placeholder="Sammlung suchen"
          aria-label="Sammlungen durchsuchen"
          className="pl-8"
        />
      </div>

      <ul aria-label="Sammlungen des Dienstes" className="max-h-64 overflow-y-auto rounded-md border p-1">
        {visible.length === 0 && (
          <li className="p-2 text-xs text-muted-foreground">Keine Sammlung gefunden.</li>
        )}
        {visible.map((collection) => (
          <li key={collection.id}>
            <button
              type="button"
              onClick={() => onChoose(collection.id)}
              className="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-accent/50"
            >
              {collection.title}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** What was chosen, and the way back to the list -- the picker itself is gone by then. */
function ChosenCollection({ title, onClear }: { title: string; onClear: () => void }) {
  return (
    <div className="flex items-center gap-2 rounded-md border bg-muted/40 px-2 py-1.5 text-xs">
      <span className="min-w-0 flex-1">
        <span className="text-muted-foreground">Sammlung </span>
        <span className="font-medium">{title}</span>
      </span>
      <Button type="button" variant="outline" size="sm" onClick={onClear}>
        Andere Sammlung
      </Button>
    </div>
  )
}

function FieldsTable({
  fields,
  selectionEnabled,
  selectedFields,
  onToggleField,
}: {
  fields: GeoportalField[]
  selectionEnabled: boolean
  selectedFields: Set<string>
  onToggleField: (name: string) => void
}) {
  return (
    <div className="max-h-48 overflow-y-auto rounded-md border">
      <Table>
        <TableHeader className="sticky top-0 z-10 bg-popover">
          <TableRow>
            {selectionEnabled && <TableHead className="h-7 w-8 text-xs" />}
            <TableHead className="h-7 text-xs">Feld</TableHead>
            <TableHead className="h-7 text-xs">Typ</TableHead>
            <TableHead className="h-7 text-xs">Werte</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {fields.map((field) => (
            <TableRow key={field.name}>
              {selectionEnabled && (
                <TableCell className="p-1.5">
                  <Checkbox
                    checked={selectedFields.has(field.name)}
                    onCheckedChange={() => onToggleField(field.name)}
                    aria-label={`Feld ${field.title}`}
                  />
                </TableCell>
              )}
              <TableCell className="p-1.5 text-xs font-medium whitespace-normal">{field.title}</TableCell>
              <TableCell className="p-1.5 text-xs text-muted-foreground">{field.dataType}</TableCell>
              <TableCell className="p-1.5 text-xs whitespace-normal text-muted-foreground">
                {formatFieldValues(field.values)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function JobProgress({ job }: { job: Job | undefined }) {
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
        <p className="text-sm tabular-nums">
          {job.processedCount.toLocaleString('de-DE')} Objekte geschrieben…
        </p>
      ) : (
        <Progress value={percent}>
          <ProgressLabel className="text-sm font-normal">Objekte werden geschrieben</ProgressLabel>
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
