import { useEffect, useRef, useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useVirtualizer } from '@tanstack/react-virtual'
import { ArrowDown, ArrowUp, Crosshair } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { formatAttributeNumber, formatCount } from '@/lib/format'
import { layerDetailQuery, type LayerField } from '@/api/layers'
import { featurePagesQuery, type Feature } from '@/api/features'
import { useSelection } from '@/state/selection'
import { FilterBar } from './FilterBar'

/** Row height in pixels. Must match the class on the row, or the virtualiser drifts. */
const ROW_HEIGHT = 26

/** How many rows before the end trigger loading the next page. */
const PREFETCH_ROWS = 40

interface AttributeTableProps {
  layerId: string | null
  layerName?: string
  /**
   * Takes only the fid: the table loads rows without geometry, because carrying polygons
   * for 200 rows costs far more than fetching one when somebody actually zooms.
   */
  onZoomToFeature: (fid: number) => void
}

export function AttributeTable({ layerId, layerName, onZoomToFeature }: AttributeTableProps) {
  const [sort, setSort] = useState<{ field: string; desc: boolean } | null>(null)
  const [filter, setFilter] = useState('')

  const { data: layer } = useQuery({
    ...layerDetailQuery(layerId ?? ''),
    enabled: Boolean(layerId),
  })

  const query = useInfiniteQuery({
    ...featurePagesQuery({
      layerId: layerId ?? '',
      sort: sort?.field,
      desc: sort?.desc,
      filter,
    }),
    enabled: Boolean(layerId),
  })

  const rows = query.data?.rows ?? []
  const total = query.data?.totalCount ?? 0

  const scrollRef = useRef<HTMLDivElement>(null)

  // Sorting and filtering happen on the server, so a change means a different result set
  // under the same scroll position -- staying where we were would show row 800 of a
  // query that just started over.
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 })
  }, [sort, filter])

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 12,
  })

  const virtualRows = virtualizer.getVirtualItems()
  const lastVisible = virtualRows.at(-1)?.index ?? 0

  // Load ahead of the viewport rather than at the very bottom: reaching the end and
  // then waiting for a request is what makes an endless table feel like it stalls.
  useEffect(() => {
    if (
      lastVisible >= rows.length - PREFETCH_ROWS &&
      query.hasNextPage &&
      !query.isFetchingNextPage
    ) {
      query.fetchNextPage()
    }
  }, [lastVisible, rows.length, query])

  if (!layerId) {
    return (
      <Panel title="Attribute">
        <Hint>Keinen Layer ausgewählt — im Layerbaum auf einen Layer klicken.</Hint>
      </Panel>
    )
  }

  const fields = layer?.fields ?? []

  return (
    <Panel
      title={`Attribute${layerName ? ` — ${layerName}` : ''}`}
      toolbar={
        <>
          <FilterBar
            fields={fields}
            value={filter}
            onChange={setFilter}
            error={query.error}
          />
          <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
            {query.isPending ? '…' : `${formatCount(rows.length)} / ${formatCount(total)}`}
          </span>
        </>
      }
    >
      {query.isError && !rows.length ? (
        <Hint variant="error">{(query.error as Error).message}</Hint>
      ) : (
        <div className="flex min-h-0 flex-1 flex-col">
          <HeaderRow fields={fields} sort={sort} onSort={setSort} />

          <div ref={scrollRef} className="min-h-0 flex-1 overflow-auto">
            {rows.length === 0 && !query.isPending ? (
              <Hint>Keine Objekte{filter ? ' für diesen Filter' : ''}.</Hint>
            ) : (
              <div className="relative" style={{ height: virtualizer.getTotalSize() }}>
                {virtualRows.map((virtualRow) => (
                  <Row
                    key={rows[virtualRow.index].fid}
                    feature={rows[virtualRow.index]}
                    fields={fields}
                    layerId={layerId}
                    top={virtualRow.start}
                    onZoom={onZoomToFeature}
                  />
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </Panel>
  )
}

/**
 * Column widths. Only the numeric and identifier columns get a fixed size; text takes
 * what is left, because that is where the content actually varies.
 */
function gridTemplate(fields: LayerField[]): string {
  const columns = fields.map((field) =>
    isNumeric(field) ? '8rem' : 'minmax(10rem, 1fr)',
  )
  return ['4.5rem', ...columns, '2rem'].join(' ')
}

function isNumeric(field: LayerField): boolean {
  return /^(integer|bigint|double precision|numeric|real|smallint)/.test(field.dataType)
}

function HeaderRow({
  fields,
  sort,
  onSort,
}: {
  fields: LayerField[]
  sort: { field: string; desc: boolean } | null
  onSort: (sort: { field: string; desc: boolean } | null) => void
}) {
  function toggle(field: string) {
    // Three states in sequence: ascending, descending, unsorted. Without the third,
    // there is no way back to the layer's natural order once a column was clicked.
    if (sort?.field !== field) return onSort({ field, desc: false })
    if (!sort.desc) return onSort({ field, desc: true })
    return onSort(null)
  }

  return (
    <div
      className="grid shrink-0 items-center border-b bg-muted/40 text-xs font-medium text-muted-foreground"
      style={{ gridTemplateColumns: gridTemplate(fields) }}
    >
      <HeaderCell label="fid" active={!sort} onClick={() => onSort(null)} align="right" />
      {fields.map((field) => (
        <HeaderCell
          key={field.id}
          label={field.sourceName}
          align={isNumeric(field) ? 'right' : 'left'}
          active={sort?.field === field.sourceName}
          descending={sort?.desc}
          onClick={() => toggle(field.sourceName)}
        />
      ))}
      <span />
    </div>
  )
}

function HeaderCell({
  label,
  align,
  active,
  descending,
  onClick,
}: {
  label: string
  align: 'left' | 'right'
  active?: boolean
  descending?: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={label}
      className={cn(
        'flex h-6 items-center gap-1 truncate px-2 hover:text-foreground',
        align === 'right' && 'justify-end',
        active && 'text-foreground',
      )}
    >
      <span className="truncate">{label}</span>
      {active && descending !== undefined && (
        descending ? <ArrowDown className="size-3 shrink-0" /> : <ArrowUp className="size-3 shrink-0" />
      )}
    </button>
  )
}

function Row({
  feature,
  fields,
  layerId,
  top,
  onZoom,
}: {
  feature: Feature
  fields: LayerField[]
  layerId: string
  top: number
  onZoom: (fid: number) => void
}) {
  const isSelected = useSelection(
    (state) => state.layerId === layerId && state.selected.has(feature.fid),
  )
  const toggle = useSelection((state) => state.toggle)

  return (
    <div
      // Absolutely positioned by the virtualiser: only the visible rows exist in the
      // DOM, and their offset is what puts them in the right place inside the spacer.
      className={cn(
        'absolute inset-x-0 grid h-[26px] items-center border-b border-border/50 text-xs',
        isSelected ? 'bg-accent' : 'hover:bg-accent/40',
      )}
      style={{ top, gridTemplateColumns: gridTemplate(fields) }}
      onClick={() => toggle(layerId, feature.fid)}
    >
      <span className="truncate px-2 text-right text-muted-foreground tabular-nums">
        {feature.fid}
      </span>
      {fields.map((field) => (
        <Cell key={field.id} value={feature.properties[field.columnName]} numeric={isNumeric(field)} />
      ))}
      <Button
        variant="ghost"
        size="icon-sm"
        className="size-5"
        aria-label={`Auf Objekt ${feature.fid} zoomen`}
        onClick={(event) => {
          event.stopPropagation()
          onZoom(feature.fid)
        }}
      >
        <Crosshair className="size-3" />
      </Button>
    </div>
  )
}

function Cell({ value, numeric }: { value: unknown; numeric: boolean }) {
  // NULL and an empty string are different states and have to look different, otherwise
  // a missing value is indistinguishable from a blank one.
  if (value === null || value === undefined) {
    return <span className="px-2 text-muted-foreground/50 italic">NULL</span>
  }

  const text = typeof value === 'number' ? formatAttributeNumber(value) : String(value)
  return (
    <span
      className={cn('truncate px-2', numeric && 'text-right tabular-nums')}
      title={text}
    >
      {text}
    </span>
  )
}

function Panel({
  title,
  toolbar,
  children,
}: {
  title: string
  toolbar?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-7 shrink-0 items-center gap-2 border-b bg-muted/40 px-2">
        <span className="shrink-0 text-xs font-medium tracking-wide uppercase text-muted-foreground">
          {title}
        </span>
        {toolbar}
      </div>
      {children}
    </div>
  )
}

function Hint({ children, variant }: { children: React.ReactNode; variant?: 'error' }) {
  return (
    <div className="flex flex-1 items-center justify-center p-4 text-center">
      <p className={cn('text-sm', variant === 'error' ? 'text-destructive' : 'text-muted-foreground')}>
        {children}
      </p>
    </div>
  )
}
