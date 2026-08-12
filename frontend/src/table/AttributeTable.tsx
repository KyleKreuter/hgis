import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
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
import type { FilterMode } from './filterMode'
import { TableEditToolbar } from './TableEditToolbar'
import { FieldInput } from './FieldInput'
import { kindOf } from './fieldKind'
import {
  advanceFocus,
  editKeyAction,
  focusKeyAction,
  moveFocus,
  type CellPosition,
} from './cellNavigation'
import { isUnknownSortFieldError } from './sortValidity'
import { cellValue, hasEdit } from './tableEditSession'
import { useTableEditing } from './useTableEditing'

/** Row height in pixels. Must match the class on the row, or the virtualiser drifts. */
const ROW_HEIGHT = 26

/** How many rows before the end trigger loading the next page. */
const PREFETCH_ROWS = 40

interface AttributeTableProps {
  layerId: string | null
  layerName?: string
  /** Needed to save edits: `POST /api/layers/{layerId}/edits` invalidates project queries too. */
  projectId: string
  /**
   * Takes only the fid: the table loads rows without geometry, because carrying polygons
   * for 200 rows costs far more than fetching one when somebody actually zooms.
   */
  onZoomToFeature: (fid: number) => void
  /**
   * Starts the table's edit mode. Handled by the workspace route rather than here,
   * because it first has to resolve a possible conflict with the map's own edit mode
   * (CONTRACT.md) -- something this component has no view of.
   */
  onRequestEdit: () => void
}

export function AttributeTable({
  layerId,
  layerName,
  projectId,
  onZoomToFeature,
  onRequestEdit,
}: AttributeTableProps) {
  // Keyed by columnName, not the display name shown in the header -- columnName never
  // changes when a field is renamed (ManageFieldsDialog, CONTRACT.md), so a rename of
  // the currently sorted field can never leave this pointed at a name the server no
  // longer resolves.
  const [sort, setSort] = useState<{ field: string; desc: boolean } | null>(null)
  // 'search' is the default: CONTRACT.md frames the syntax-free search as the common
  // case ("für den häufigsten Fall"), so that is what a layer opens into. One shared
  // text state, not one per mode: switching mode clears the field (see
  // `handleModeChange`), so `filter` and `search` are never both non-empty at once --
  // which of the two `text` becomes is decided purely by `mode`.
  const [mode, setMode] = useState<FilterMode>('search')
  const [text, setText] = useState('')
  const filter = mode === 'filter' ? text : ''
  const search = mode === 'search' ? text : ''

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
      search,
    }),
    enabled: Boolean(layerId),
  })

  const rows = query.data?.rows ?? []
  const total = query.data?.totalCount ?? 0
  const fields = layer?.fields ?? []

  function handleModeChange(next: FilterMode) {
    setMode(next)
    setText('')
  }

  const scrollRef = useRef<HTMLDivElement>(null)

  // Sorting and filtering happen on the server, so a change means a different result set
  // under the same scroll position -- staying where we were would show row 800 of a
  // query that just started over. An open cell editor is pointed at a row/column
  // position in the set that is about to be replaced, so it is committed here first --
  // `rows`/`fields` are still the pre-change data at this point, the query only
  // refetches after this effect runs.
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 })
    const state = useTableEditing.getState()
    if (!state.editingCell) return
    const feature = rows[state.editingCell.row]
    const field = fields[state.editingCell.column]
    if (feature && field) {
      state.commitEditing(
        feature.fid,
        field.columnName,
        feature.rowVersion,
        feature.properties[field.columnName],
        state.editingCell,
      )
    } else {
      state.cancelEditing()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sort, filter, search])

  // A field deleted while it was the active sort (ManageFieldsDialog, CONTRACT.md
  // "Attributfelder löschen") leaves this pointed at a column the server no longer
  // knows -- the next fetch answers with exactly that 400. Falling back to unsorted here
  // is simpler and more honest than trying to purge the deleted field out of this state
  // the moment the dialog deletes it, from a wholly different part of the page. The
  // filter goes through the same kind of error but is deliberately left alone -- see
  // `isUnknownSortFieldError`.
  useEffect(() => {
    if (sort && isUnknownSortFieldError(query.error)) setSort(null)
  }, [sort, query.error])

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

  const active = useTableEditing((state) => state.active)
  const focus = useTableEditing((state) => state.focus)
  const editingCell = useTableEditing((state) => state.editingCell)
  const rowCount = rows.length
  const columnCount = fields.length

  // Entering edit mode with nothing focused yet -- start at the top-left cell, once
  // there is a top-left cell to start at (the first page may still be loading).
  useEffect(() => {
    if (active && rowCount > 0 && !useTableEditing.getState().focus) {
      useTableEditing.getState().setFocus({ row: 0, column: 0 })
    }
  }, [active, rowCount])

  // Keeps the focused row in view while the arrow keys move it -- otherwise keyboard
  // navigation quietly walks off the visible window of a virtualised table.
  useEffect(() => {
    if (active && focus) virtualizer.scrollToIndex(focus.row, { align: 'auto' })
  }, [active, focus, virtualizer])

  // Keyboard events need somewhere to land. While a cell is open for editing, its own
  // input holds real DOM focus (so typing works); otherwise the scroll container does,
  // so arrow keys and Enter reach `handleKeyDown` no matter which row last had focus
  // before being scrolled out of the DOM.
  useEffect(() => {
    if (active && !editingCell) scrollRef.current?.focus({ preventScroll: true })
  }, [active, focus, editingCell])

  /**
   * Commits whatever cell is currently open for editing (if any) and moves focus to
   * `position`. The single path both a click on another cell and a confirmed keyboard
   * edit (Enter/Tab) go through -- neither should ever leave a draft behind unwritten.
   */
  function focusCell(position: CellPosition) {
    const state = useTableEditing.getState()
    if (!state.editingCell) {
      state.setFocus(position)
      return
    }
    const feature = rows[state.editingCell.row]
    const field = fields[state.editingCell.column]
    if (!feature || !field) {
      state.cancelEditing()
      state.setFocus(position)
      return
    }
    state.commitEditing(feature.fid, field.columnName, feature.rowVersion, feature.properties[field.columnName], position)
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (!active) return
    const state = useTableEditing.getState()

    if (state.editingCell) {
      const action = editKeyAction(event)
      if (!action) return
      event.preventDefault()
      if (action.type === 'cancel') {
        // Escape: drop the draft, the committed value (if any) stays as it was.
        state.cancelEditing()
        return
      }
      focusCell(advanceFocus(state.editingCell, action.advance, rowCount, columnCount))
      return
    }

    if (!focus) return

    if (event.key === 'Escape') {
      // Nothing is open for editing here -- Escape instead reverts a committed change
      // on the focused cell, the same "do whichever is still undone" idea the
      // measuring tool uses for its own Escape handling.
      const feature = rows[focus.row]
      const field = fields[focus.column]
      if (feature && field && hasEdit(state, feature.fid, field.columnName)) {
        event.preventDefault()
        state.revertCell(feature.fid, field.columnName)
      }
      return
    }

    const action = focusKeyAction(event)
    if (!action) return

    if (action.type === 'move') {
      event.preventDefault()
      state.setFocus(moveFocus(focus, action.direction, rowCount, columnCount))
      return
    }

    const feature = rows[focus.row]
    const field = fields[focus.column]
    if (!feature || !field) return
    const kind = kindOf(field.dataType)
    if (kind === 'readonly') return

    const current = cellValue(state, feature.fid, field.columnName, feature.properties[field.columnName])

    if (action.type === 'startEdit') {
      event.preventDefault()
      state.startEditing(focus, current)
    } else if (kind === 'text' || kind === 'integer' || kind === 'decimal') {
      // Typing over a focused cell overwrites it, like a spreadsheet -- date, time and
      // boolean fields use their own picker/select and gain nothing from that, so only
      // the freely-typed kinds react to a bare character key.
      event.preventDefault()
      state.startEditing(focus, action.char)
    }
  }

  if (!layerId) {
    return (
      <Panel title="Attribute">
        <Hint>Kein Layer ausgewählt. Klicken Sie im Layerbaum auf einen Layer.</Hint>
      </Panel>
    )
  }

  return (
    <Panel
      title={`Attribute${layerName ? ` - ${layerName}` : ''}`}
      toolbar={
        <>
          <FilterBar
            fields={fields}
            layerId={layerId}
            mode={mode}
            onModeChange={handleModeChange}
            value={text}
            onChange={setText}
            error={query.error}
            totalCount={total}
          />
          <TableEditToolbar layerId={layerId} projectId={projectId} onRequestStart={onRequestEdit} />
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

          <div
            ref={scrollRef}
            className="min-h-0 flex-1 overflow-auto outline-none"
            tabIndex={active ? 0 : -1}
            onKeyDown={handleKeyDown}
          >
            {rows.length === 0 && !query.isPending ? (
              <Hint>
                Keine Objekte{text ? (mode === 'filter' ? ' für diesen Filter' : ' für diese Suche') : ''}.
              </Hint>
            ) : (
              <div className="relative" style={{ height: virtualizer.getTotalSize() }}>
                {virtualRows.map((virtualRow) => (
                  <Row
                    key={rows[virtualRow.index].fid}
                    feature={rows[virtualRow.index]}
                    fields={fields}
                    layerId={layerId}
                    rowIndex={virtualRow.index}
                    editable={active}
                    top={virtualRow.start}
                    onZoom={onZoomToFeature}
                    onFocusCell={focusCell}
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
  function toggle(columnName: string) {
    // Three states in sequence: ascending, descending, unsorted. Without the third,
    // there is no way back to the layer's natural order once a column was clicked.
    if (sort?.field !== columnName) return onSort({ field: columnName, desc: false })
    if (!sort.desc) return onSort({ field: columnName, desc: true })
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
          active={sort?.field === field.columnName}
          descending={sort?.desc}
          onClick={() => toggle(field.columnName)}
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
  rowIndex,
  editable,
  top,
  onZoom,
  onFocusCell,
}: {
  feature: Feature
  fields: LayerField[]
  layerId: string
  rowIndex: number
  editable: boolean
  top: number
  onZoom: (fid: number) => void
  onFocusCell: (position: CellPosition) => void
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
        'absolute inset-x-0 grid h-[26px] items-stretch border-b border-border/50 text-xs',
        isSelected ? 'bg-accent' : 'hover:bg-accent/40',
      )}
      style={{ top, gridTemplateColumns: gridTemplate(fields) }}
      onClick={() => toggle(layerId, feature.fid)}
    >
      <span className="flex items-center justify-end truncate px-2 text-right text-muted-foreground tabular-nums">
        {feature.fid}
      </span>
      {fields.map((field, columnIndex) => (
        <EditableCell
          key={field.id}
          feature={feature}
          field={field}
          numeric={isNumeric(field)}
          editable={editable}
          rowIndex={rowIndex}
          columnIndex={columnIndex}
          onFocusCell={onFocusCell}
        />
      ))}
      <span className="flex items-center justify-center">
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
      </span>
    </div>
  )
}

/**
 * One attribute cell -- a plain value when the table is not in edit mode or the column
 * cannot be edited (`uuid`/`bytea`), otherwise a focusable, editable one.
 *
 * Subscribes narrowly to the store (own fid+column only) so a keystroke in one cell
 * does not re-render every other cell in the viewport -- `draft` in particular changes
 * on every keystroke, so only the one cell actually being typed into reads it.
 */
function EditableCell({
  feature,
  field,
  numeric,
  editable,
  rowIndex,
  columnIndex,
  onFocusCell,
}: {
  feature: Feature
  field: LayerField
  numeric: boolean
  editable: boolean
  rowIndex: number
  columnIndex: number
  onFocusCell: (position: CellPosition) => void
}) {
  const isFocused = useTableEditing(
    (state) => state.focus?.row === rowIndex && state.focus?.column === columnIndex,
  )
  const isEditing = useTableEditing(
    (state) => state.editingCell?.row === rowIndex && state.editingCell?.column === columnIndex,
  )
  const isDirty = useTableEditing((state) => hasEdit(state, feature.fid, field.columnName))
  const editedValue = useTableEditing((state) => state.edits.get(feature.fid)?.[field.columnName])
  // Only the cell actually being edited reads `draft` -- everyone else gets a stable
  // `undefined`, so a keystroke does not ripple through the rest of the row.
  const draft = useTableEditing((state) => (isEditing ? state.draft : undefined))

  const kind = kindOf(field.dataType)
  const original = feature.properties[field.columnName]
  const value = isDirty ? editedValue : original

  const editorRef = useRef<HTMLSpanElement>(null)
  // `autoFocus` only reaches a plain `<input>` -- the boolean kind renders a Select
  // whose trigger is a button, so focus is grabbed by hand here instead. Runs for every
  // kind uniformly rather than special-casing boolean.
  useEffect(() => {
    if (!isEditing) return
    editorRef.current?.querySelector<HTMLElement>('input, [data-slot="select-trigger"]')?.focus()
  }, [isEditing])

  if (isEditing) {
    return (
      <span ref={editorRef} className="flex items-center px-1" onClick={(event) => event.stopPropagation()}>
        <FieldInput
          kind={kind}
          value={draft}
          onChange={(next) => useTableEditing.getState().setDraft(next)}
          className="h-[22px] text-xs"
        />
      </span>
    )
  }

  const canEdit = editable && kind !== 'readonly'

  return (
    <span
      className={cn(
        'relative flex min-w-0 items-center px-2',
        numeric && 'justify-end text-right tabular-nums',
        canEdit && 'cursor-text',
        isFocused && 'ring-1 ring-inset ring-ring',
        isDirty && 'bg-foreground/[0.06]',
      )}
      title={value === null || value === undefined ? undefined : String(value)}
      onClick={editable ? () => onFocusCell({ row: rowIndex, column: columnIndex }) : undefined}
    >
      {value === null || value === undefined ? (
        <span className="min-w-0 flex-1 truncate text-muted-foreground/50 italic">NULL</span>
      ) : (
        <span className="min-w-0 flex-1 truncate">
          {typeof value === 'number' ? formatAttributeNumber(value) : String(value)}
        </span>
      )}
      {/* A dot rather than a colour so the monochrome palette stays monochrome. */}
      {isDirty && <span className="absolute top-1 right-1 size-1 rounded-full bg-foreground/70" aria-hidden />}
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
