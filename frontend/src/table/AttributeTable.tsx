import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useVirtualizer } from '@tanstack/react-virtual'
import { ArrowDown, ArrowUp, Crosshair } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { formatAttributeNumber, formatCount } from '@/lib/format'
import { layerDetailQuery, type LayerField, type LayerKind } from '@/api/layers'
import { featurePagesQuery, fetchFeatureFids, type Feature } from '@/api/features'
import { useSelection } from '@/state/selection'
import type { ViewStateWriter } from '@/state/useViewState'
import {
  layerStateOf,
  queryOf,
  restoredQueryHidesData,
  survivingSelection,
  SELECTION_SAVE_LIMIT,
} from '@/state/viewState'
import { FilterBar } from './FilterBar'
import type { FilterMode } from './filterMode'
import { layerTableStateOf } from './layerTableState'
import { TableEditToolbar } from './TableEditToolbar'
import { FieldInput } from './FieldInput'
import { initialDraftFromChar, kindOf } from './fieldKind'
import {
  advanceFocus,
  editKeyAction,
  focusKeyAction,
  moveFocus,
  type CellPosition,
} from './cellNavigation'
import { isUnknownFilterFieldError } from './filterValidity'
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
  /**
   * Missing or `'VECTOR'` for an ordinary layer; `'WMS'` for a Kartenbild, which has no
   * attributes at all -- the table shows one sentence instead of querying features that
   * do not exist (plan Stufe 4, "ein klarer Satz statt einer leeren Tabelle").
   */
  layerKind?: LayerKind
  /** The layer's unrestricted feature count -- what a restored filter is compared against
   *  to say how much it hides (CONTRACT.md phase 17, "Ein gespeicherter Filter versteckt
   *  Daten"). `undefined` while the layer list is still loading. */
  layerFeatureCount?: number
  /** Needed to save edits: `POST /api/layers/{layerId}/edits` invalidates project queries too. */
  projectId: string
  /** The project's working-state read/write path (CONTRACT.md phase 17, schema B), held
   *  by the workspace route so it lives and flushes for the whole session, not per layer. */
  viewState: ViewStateWriter
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
  layerKind,
  layerFeatureCount,
  projectId,
  viewState,
  onZoomToFeature,
  onRequestEdit,
}: AttributeTableProps) {
  // A Kartenbild has no fields and no features to page through -- `layerId` alone
  // cannot say that (it names the layer regardless of kind), so every query below is
  // also gated on this.
  const isMapImage = layerKind === 'WMS'
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
  // Whether the current `mode`/`text` came from the saved working state rather than
  // something just typed -- what tells the restored-filter hint below apart from an
  // ordinary, freshly-entered one the user already knows is active (CONTRACT.md phase 17
  // rule 1, "Ein gespeicherter Filter versteckt Daten"). Cleared by any of the user's own
  // edits: `handleModeChange`, `handleTextChange`, and the hint's own reset control.
  const [restoredQuery, setRestoredQuery] = useState(false)
  const filter = mode === 'filter' ? text : ''
  const search = mode === 'search' ? text : ''

  // Which layers have already been seeded from the saved working state this session. Read
  // by the seed below to tell a first visit from a return, and written by the selection
  // effect next to it. A one-time seed from `viewState.document`, not something that should
  // run again just because a later write changes that document.
  const restoredLayers = useRef<Set<string>>(new Set())
  // Set right before a restored selection is written into the store, so the write it
  // triggers (the subscription below fires on every store change) does not turn straight
  // around and PUT the exact value it just read back.
  const suppressSelectionEcho = useRef(false)
  // The unmount/dependency-light effects below read the latest writer through this
  // instead of closing over `viewState`, the same reasoning as `useStyleEditor`'s `saveRef`.
  const viewStateRef = useRef(viewState)
  viewStateRef.current = viewState

  const { data: layer } = useQuery({
    ...layerDetailQuery(layerId ?? ''),
    enabled: Boolean(layerId) && !isMapImage,
  })

  const query = useInfiniteQuery({
    ...featurePagesQuery({
      layerId: layerId ?? '',
      sort: sort?.field,
      desc: sort?.desc,
      filter,
      search,
    }),
    enabled: Boolean(layerId) && !isMapImage,
  })

  const rows = query.data?.rows ?? []
  const total = query.data?.totalCount ?? 0
  const fields = layer?.fields ?? []

  // Every one of these three is itself the action CONTRACT.md's "Die wichtigste Regel"
  // asks for: written right where the user sorts, searches or switches mode, never from
  // an effect watching `sort`/`mode`/`text` -- those fall back to their initial values
  // whenever a different layer's local state has not been restored yet, and an effect
  // reacting to that would overwrite this layer's saved state with those defaults.

  function handleSortChange(next: { field: string; desc: boolean } | null) {
    setSort(next)
    if (layerId) viewState.writeSort(layerId, next)
  }

  function handleModeChange(next: FilterMode) {
    setMode(next)
    setText('')
    setRestoredQuery(false)
    if (layerId) viewState.writeQuery(layerId, null)
  }

  function handleTextChange(next: string) {
    setText(next)
    setRestoredQuery(false)
    if (layerId) viewState.writeQuery(layerId, queryOf(mode, next))
  }

  function resetRestoredQuery() {
    setText('')
    setRestoredQuery(false)
    if (layerId) viewState.writeQuery(layerId, null)
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
  // the moment the dialog deletes it, from a wholly different part of the page.
  useEffect(() => {
    if (sort && isUnknownSortFieldError(query.error)) setSort(null)
  }, [sort, query.error])

  // The filter/search expression goes through the same kind of error -- a field it names
  // can be deleted after the fact, whether the expression was just typed or restored from
  // a previous session (CONTRACT.md phase 17 rule 2). Recovered the same way as the sort
  // above: dropped locally so the table keeps working, not written back out, since a
  // client-side self-heal is not itself something the user did.
  useEffect(() => {
    if (text && isUnknownFilterFieldError(query.error)) {
      setText('')
      setRestoredQuery(false)
    }
  }, [text, query.error])

  /**
   * Which layer the `sort`/`mode`/`text` above currently describe.
   *
   * State rather than a ref because the seed below reads it while rendering, and that is
   * the whole point of it: the render that brings a new `layerId` still holds the previous
   * layer's sort and query, and `featurePagesQuery` is built from all four at once. Seeding
   * from an effect therefore let exactly one request go out with the layer just opened and
   * the filter of the one just left. Setting state during rendering makes React drop that
   * pass and run the component again with the seeded values -- the dropped pass reaches no
   * effect, so react-query never gets to start the request.
   */
  const [seededLayerId, setSeededLayerId] = useState<string | null>(null)

  // Seeds this layer's sort and query from the saved working state (CONTRACT.md phase 17,
  // schema B). A one-shot seed per layer, not a subscription that keeps the two in sync
  // with `viewState.document`: the latter would also fire every time this layer's own
  // write lands back in the document a moment later, undoing whatever the user just did
  // in between.
  //
  // Reset *and* restore, never restore-only: this component stays mounted across a layer
  // switch, so whatever is not written here is simply inherited from the layer before --
  // see `layerTableStateOf`, which is where the whole decision lives.
  if (viewState.ready && layerId !== seededLayerId) {
    setSeededLayerId(layerId)
    if (layerId) {
      // `restoredLayers` is only ever written from the effect below, so this read is the
      // same on both passes of a StrictMode double render.
      const table = layerTableStateOf(
        layerStateOf(viewState.document, layerId),
        !restoredLayers.current.has(layerId),
      )
      setSort(table.sort)
      setMode(table.mode)
      setText(table.text)
      setRestoredQuery(table.restoredQuery)
    }
  }

  // Restores the saved selection, once per layer per session -- the one part of the saved
  // state that costs a request, which is why it stays in an effect and stays at the first
  // visit. The layer switch itself already cleared the selection (`selectLayer` in the
  // workspace route), so there is nothing here to reset on a later visit.
  useEffect(() => {
    if (!layerId || !viewState.ready) return
    const firstVisit = !restoredLayers.current.has(layerId)
    // Marks the layer as seeded whether or not there is a selection to fetch: this is what
    // the seed above reads to tell a first visit from a return.
    restoredLayers.current.add(layerId)

    const saved = layerStateOf(viewState.document, layerId)
    if (!firstVisit || saved.selection.length === 0) return

    let cancelled = false
    // The selection as it stands before the request goes out. Every action that changes
    // it puts a new Set in the store, so a different identity on arrival means the user
    // selected something themselves in the meantime -- that is the newer intent, and a
    // seed from a saved state must not overtake it.
    const before = useSelection.getState().selected
    // No endpoint answers "do these fids still exist" directly, so this reuses the
    // fids endpoint "select all matches" already relies on, unfiltered -- the layer's
    // complete current fid set, fids only, no attributes or geometry (CONTRACT.md
    // rule 3, "Die Auswahl zeigt auf gelöschte Objekte").
    fetchFeatureFids({ layerId })
      .then(({ fids }) => {
        if (cancelled || useSelection.getState().selected !== before) return
        const surviving = survivingSelection(saved.selection, new Set(fids))
        if (surviving.length === 0) {
          toast.error('Das Programm konnte die gespeicherte Auswahl nicht wiederherstellen')
          return
        }
        // Raised for exactly the store write it belongs to, and lowered again the
        // moment it returns: the subscription below runs synchronously inside `select`,
        // so a flag held for the whole request would swallow every selection the user
        // makes while it is in flight -- and their selections are worth saving.
        suppressSelectionEcho.current = true
        try {
          useSelection.getState().select(layerId, surviving, 'replace')
        } finally {
          suppressSelectionEcho.current = false
        }
      })
      .catch(() => {
        if (cancelled) return
        toast.error('Das Programm konnte die gespeicherte Auswahl nicht wiederherstellen')
      })

    return () => {
      // Switching layers while the fids are still in flight: the answer describes the
      // layer that was left. Applying it would put that layer's highlight on the map
      // while a different one is open, and the fids mean nothing there anyway.
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layerId, viewState.ready])

  // Persists every selection change made while this layer is active -- a store
  // subscription rather than a `useSelection(...)` read plus effect, because the writes
  // this needs to react to happen in files this package does not own (`map/IdentifyControl`,
  // `map/RectangleSelectTool`, `SelectAllMatchesButton` below). Subscribing only fires
  // for an actual `set()`, i.e. an actual selection action -- never merely because this
  // component re-rendered or `layerId` changed, which is what keeps this from ever
  // writing a default it did not ask for (CONTRACT.md's "Die wichtigste Regel").
  useEffect(() => {
    if (!layerId) return
    return useSelection.subscribe((state, previous) => {
      if (state.selected === previous.selected || state.layerId !== layerId) return
      // Raised and lowered by the restore above, around its own `select` call and nothing
      // more -- so a selection that reaches this point is always one the user made, never
      // the value that was just read back off the server.
      if (suppressSelectionEcho.current) return
      const written = viewStateRef.current.writeSelection(layerId, [...state.selected])
      if (!written) {
        toast.error('Das Programm konnte die Auswahl nicht speichern', {
          description: `Es sind mehr als ${formatCount(SELECTION_SAVE_LIMIT)} Objekte ausgewählt`,
        })
      }
    })
  }, [layerId])

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
      //
      // The character goes through `initialDraftFromChar`, not into the draft raw: on a
      // numeric column the raw text is what gets saved if the user presses Enter without
      // typing anything more, and the server rejects it.
      event.preventDefault()
      state.startEditing(focus, initialDraftFromChar(action.char, kind))
    }
  }

  if (!layerId) {
    return (
      <Panel title="Attribute">
        <Hint>Kein Layer ausgewählt. Klicken Sie im Layerbaum auf einen Layer.</Hint>
      </Panel>
    )
  }

  // A Kartenbild is a picture, not a table -- there are no fields and no rows to page
  // through, so this stops before any of the feature/edit machinery below runs.
  if (isMapImage) {
    return (
      <Panel title={`Attribute${layerName ? ` - ${layerName}` : ''}`}>
        <Hint>Ein Kartenbild hat keine Attribute.</Hint>
      </Panel>
    )
  }

  // Only while the restored filter/search still has not been touched, and only while it
  // is actually hiding something -- one that matched everything the layer has is not
  // worth a hint over (CONTRACT.md phase 17 rule 1).
  const showRestoredHint =
    restoredQuery && text !== '' && layerFeatureCount !== undefined && restoredQueryHidesData(total, layerFeatureCount)

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
            onChange={handleTextChange}
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
      {showRestoredHint && (
        <RestoredQueryHint mode={mode} matchedCount={total} totalCount={layerFeatureCount} onReset={resetRestoredQuery} />
      )}
      {query.isError && !rows.length ? (
        <Hint variant="error">{(query.error as Error).message}</Hint>
      ) : (
        <div className="flex min-h-0 flex-1 flex-col">
          {/*
           * The header sits inside the scroller, not above it. Kept outside, it formed a
           * second horizontal scroll layer: dragging the rows sideways left the header
           * behind, so labels no longer matched their values. It also came out narrower
           * than the rows -- the body reserves width for its vertical scrollbar, and the
           * 1fr columns divide up whatever is left, so the two never agreed on a width.
           * One scroller settles both: same width by construction, same offset always.
           */}
          <div
            ref={scrollRef}
            className="min-h-0 flex-1 overflow-auto outline-none"
            tabIndex={active ? 0 : -1}
            onKeyDown={handleKeyDown}
          >
            <HeaderRow fields={fields} sort={sort} onSort={handleSortChange} />
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
      /*
       * Carries no background of its own -- each HeaderCell paints its own, see there.
       * Opaque rather than the previous bg-muted/40, because inside the scroller the rows
       * now pass underneath and would show through anything translucent; the color-mix
       * reproduces exactly what 40% muted over the background used to look like.
       */
      className="sticky top-0 z-10 grid shrink-0 items-center text-xs font-medium text-muted-foreground"
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
      {/* Matches the trailing 2rem track of gridTemplate. Paints like a cell so the
          header's strip does not stop short of the row-action column. */}
      <span className="h-6 border-b bg-[color-mix(in_oklab,var(--muted)_40%,var(--background))]" />
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
        // Background and bottom border live on the cell, not on the grid around it. That
        // grid is only as wide as the scroller (the columns overflow it), so a background
        // on it would cover nothing once scrolled sideways and the rows would show
        // through the header. The cells are laid out across the full scroll width.
        'flex h-6 items-center gap-1 truncate border-b bg-[color-mix(in_oklab,var(--muted)_40%,var(--background))] px-2 hover:text-foreground',
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
        'absolute inset-x-0 grid h-[26px] items-stretch text-xs',
        isSelected ? 'bg-accent' : 'hover:bg-accent/40',
      )}
      style={{ top, gridTemplateColumns: gridTemplate(fields) }}
      onClick={() => toggle(layerId, feature.fid)}
    >
      {/*
       * bg-inherit and border-b on every cell, here and in EditableCell: the row is
       * positioned inset-x-0 and is therefore exactly as wide as the scroller, while its
       * columns overflow that width. Selection, hover and the dividing line painted on
       * the row alone stopped at the old viewport edge, leaving the columns further right
       * unmarked and undivided. The cells span the full scroll width, so putting both on
       * them carries them across -- the same reasoning HeaderCell already follows.
       */}
      <span className="flex items-center justify-end truncate border-b border-border/50 bg-inherit px-2 text-right text-muted-foreground tabular-nums">
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
      <span className="flex items-center justify-center border-b border-border/50 bg-inherit">
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
      <span
        ref={editorRef}
        className="flex items-center border-b border-border/50 bg-inherit px-1"
        onClick={(event) => event.stopPropagation()}
      >
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
        // bg-inherit and border-b: see the fid cell in Row. The edited-value tint sits on
        // top as an overlay rather than as a background of its own, which would replace
        // the inherited one -- a cell that is both edited and in a selected row has to
        // show both, exactly as it did when the tint was layered over the row's colour.
        'relative flex min-w-0 items-center border-b border-border/50 bg-inherit px-2',
        numeric && 'justify-end text-right tabular-nums',
        canEdit && 'cursor-text',
        isFocused && 'ring-1 ring-inset ring-ring',
        isDirty && 'after:pointer-events-none after:absolute after:inset-0 after:bg-foreground/[0.06]',
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

/**
 * Tells the user a restored filter/search is still limiting what they see -- without
 * this, "342 von 5.108 Objekten" reads as if the layer only ever had 342 (CONTRACT.md
 * phase 17 rule 1, "Ein gespeicherter Filter versteckt Daten"). Sits between the toolbar
 * and the header row rather than inside `FilterBar` itself, the same reasoning
 * `FilterBar`'s own comment gives for keeping its row lean: this needs room a slim
 * toolbar strip does not have.
 */
function RestoredQueryHint({
  mode,
  matchedCount,
  totalCount,
  onReset,
}: {
  mode: FilterMode
  matchedCount: number
  totalCount: number
  onReset: () => void
}) {
  return (
    <div className="flex h-6 shrink-0 items-center gap-2 border-b bg-muted/40 px-2 text-xs text-muted-foreground">
      <span>
        Der gespeicherte {mode === 'filter' ? 'Filterausdruck' : 'Suchbegriff'} zeigt{' '}
        {formatCount(matchedCount)} von {formatCount(totalCount)} Objekten.
      </span>
      <Button variant="link" size="xs" className="h-auto p-0 text-xs" onClick={onReset}>
        {mode === 'filter' ? 'Filter löschen' : 'Suche löschen'}
      </Button>
    </div>
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
      {/*
       * h-7, fixed, not min-h-7: this dock is resizable down to 8% of the window
       * (`WorkspaceLayout.tsx`, `minSize="8%"`), and a strip that grows taller than one
       * line eats that height from the table below it -- `flex-wrap` was tried first and
       * rejected, because in the table's own edit mode (counter, Verwerfen, Speichern,
       * separator, X) it wrapped to four lines at 340px, which at the dock's own low end
       * left the table 0px, not merely cramped. Overflow past one line is a real
       * possibility that has to go *somewhere*; the choice here is sideways, not down --
       * `overflow-x-auto` keeps every control reachable by scrolling the strip, the same
       * way the table body itself already scrolls sideways for its columns (see the
       * scroller comment below), rather than trading a fixed-height table for a
       * growing one.
       *
       * The scrollbar utilities below (`scrollbar-width`/`scrollbar-color` for
       * Firefox, `::-webkit-scrollbar*` for the rest) force a thin, always-drawn bar
       * instead of the platform's own overlay one: an overlay scrollbar only appears on
       * hover or while dragging, so a strip that overflows by exactly the width of
       * "42 / 1.234" can look complete at rest -- there is no other hint. A native
       * scrollbar only ever renders when the content actually overflows, so this never
       * shows on a line that fits. The way out of edit mode (the X, `TableEditToolbar`)
       * is `sticky` inside this scroller and pinned to its own right edge for the same
       * reason: at 400px the cut used to land in the gap right after "Speichern",
       * which hid both the counter and the X with nothing visibly clipped to notice.
       */}
      <div className="flex h-7 shrink-0 items-center gap-2 overflow-x-auto border-b bg-muted/40 px-2 [scrollbar-color:var(--border)_transparent] [scrollbar-width:thin] [&::-webkit-scrollbar]:h-1 [&::-webkit-scrollbar-thumb]:rounded-full [&::-webkit-scrollbar-thumb]:bg-border [&::-webkit-scrollbar-track]:bg-transparent">
        {/*
         * Gives way before anything else in the strip does, and all the way down to
         * nothing. Held at its full width it pushed the save button, the change counter
         * and the whole search field out over the panel's edge -- and out of reach,
         * because the panel then scrolled sideways and carried this title along with it.
         *
         * Nothing here holds it open, unlike the layer name in the tree: this is a
         * caption, not the only place a layer is named. The same layer stands in the tree
         * a panel away, in this title attribute, and over the table's own columns. The
         * search field next to it is a control that has to stay operable, so the last
         * pixels of the strip belong to it and not here.
         */}
        <span
          className="truncate text-xs font-medium tracking-wide uppercase text-muted-foreground"
          title={title}
        >
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
