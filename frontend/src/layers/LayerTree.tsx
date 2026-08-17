import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  ChevronDown,
  ChevronUp,
  Circle,
  Columns3,
  Crop,
  Crosshair,
  Download,
  Eye,
  EyeOff,
  FileDown,
  Filter,
  FilterX,
  Globe,
  Image as ImageIcon,
  Loader2,
  Magnet,
  Map as MapIcon,
  MoreHorizontal,
  Pencil,
  Plus,
  Scissors,
  ScissorsLineDashed,
  Shapes,
  Spline,
  Square,
  Trash2,
  Upload,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Skeleton } from '@/components/ui/skeleton'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { formatCount } from '@/lib/format'
import { exportErrorMessage, useExportLayer } from '@/api/export'
import { TrashDialog } from '@/trash'
import {
  isVectorLayer,
  layerListQuery,
  useReorderLayers,
  useUpdateLayer,
  type ClipMode,
  type GeometryType,
  type LayerSummary,
} from '@/api/layers'
import { useSelection } from '@/state/selection'
import { previewColorOf } from '@/styling'
import {
  availableClipModes,
  clipMaskBadgeAriaLabel,
  clipMaskBadgeTooltip,
  clipMaskLockedReason,
  clipModeLabel,
} from './clipMask'
import { DeleteLayerDialog } from './DeleteLayerDialog'
import { useMapViewport } from '@/map/mapViewportStore'
import { GEOMETRY_LABELS } from './geometry'
import { describeZoomWindow, zoomWindowState } from './zoomWindow'
import { LayerBasemapDialog } from './LayerBasemapDialog'
import { ManageFieldsDialog } from './ManageFieldsDialog'
import { RenameLayerDialog } from './RenameLayerDialog'
import { isNoOpMove, reorderedIdsBottomToTop } from './reorder'

const GEOMETRY_ICONS: Record<GeometryType, typeof Square> = {
  MULTIPOLYGON: Square,
  MULTILINESTRING: Spline,
  MULTIPOINT: Circle,
  GEOMETRY: Shapes,
}

/**
 * The mask badge's icon, by mode. The two `*Clipped` modes keep the scissors that
 * the old `inside`/`outside` modes always used -- geometry actually gets cut there.
 * The two `*Whole` modes get `Filter`/`FilterX` instead: nothing is cut, an object is
 * only kept or dropped whole (contract "ein Symbol, das 'ganz, aber gefiltert'
 * trifft"). Direction lines up across the pair: `insideWhole`/`insideClipped` share
 * the plain shape, `outsideWhole`/`outsideClipped` the dashed/crossed one, so the same
 * direction always reads the same regardless of mode.
 */
const CLIP_MODE_ICONS: Record<ClipMode, typeof Scissors> = {
  insideWhole: Filter,
  insideClipped: Scissors,
  outsideWhole: FilterX,
  outsideClipped: ScissorsLineDashed,
}

interface LayerTreeProps {
  projectId: string
  activeLayerId: string | null
  onSelectLayer: (layerId: string | null) => void
  onZoomToLayer: (extent: [number, number, number, number]) => void
  onImportClick: () => void
  onCreateLayerClick: () => void
  onGeoportalClick: () => void
  /** Opens `AddMapImageDialog` -- the second way a Kartenbild comes in (plan Stufe 4). */
  onAddMapImageClick: () => void
  /** Layers marked as snap sources, or null when not editing -- the toggle only exists then. */
  snapSources?: string[] | null
  onToggleSnapSource?: (layerId: string) => void
}

export function LayerTree({
  projectId,
  activeLayerId,
  onSelectLayer,
  onZoomToLayer,
  onImportClick,
  onCreateLayerClick,
  onGeoportalClick,
  onAddMapImageClick,
  snapSources = null,
  onToggleSnapSource,
}: LayerTreeProps) {
  const { data: layers, isPending } = useQuery(layerListQuery(projectId))
  const reorder = useReorderLayers(projectId)

  const [renaming, setRenaming] = useState<LayerSummary | null>(null)
  const [managingFields, setManagingFields] = useState<LayerSummary | null>(null)
  const [settingBasemap, setSettingBasemap] = useState<LayerSummary | null>(null)
  const [deleting, setDeleting] = useState<LayerSummary | null>(null)
  const [trashOpen, setTrashOpen] = useState(false)
  const [draggedId, setDraggedId] = useState<string | null>(null)
  const [dropBefore, setDropBefore] = useState<number | null>(null)

  // The tree reads top-down, zIndex counts bottom-up: the highest index is drawn last
  // and therefore sits on top of the map -- and at the top of this list.
  const displayed = [...(layers ?? [])].sort((a, b) => b.zIndex - a.zIndex)

  function applyMove(from: number, before: number) {
    if (isNoOpMove(from, before)) return
    reorder.mutate(reorderedIdsBottomToTop(displayed, from, before), {
      onError: () => toast.error('Das Programm konnte die Reihenfolge nicht speichern'),
    })
  }

  function handleDrop(before: number) {
    const from = displayed.findIndex((layer) => layer.id === draggedId)
    setDraggedId(null)
    setDropBefore(null)
    applyMove(from, before)
  }

  /** One step up or down in the tree -- the keyboard-reachable counterpart to dragging. */
  function move(layer: LayerSummary, direction: -1 | 1) {
    const from = displayed.findIndex((entry) => entry.id === layer.id)
    const to = from + direction
    if (to < 0 || to >= displayed.length) return
    // Moving down by one means inserting after the next row, hence the extra step.
    applyMove(from, direction === -1 ? to : to + 1)
  }

  // Reachable from every state below, including the two that return before the layer
  // list itself would render -- a project can hold nothing but trashed layers, and the
  // Papierkorb must stay reachable then too, not only once something is left on the map.
  const trashDialog = (
    <TrashDialog projectId={projectId} open={trashOpen} onOpenChange={setTrashOpen} />
  )

  if (isPending) {
    return (
      <Panel onOpenTrash={() => setTrashOpen(true)}>
        <div className="grid gap-1.5 p-2">
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-4/5" />
        </div>
        {trashDialog}
      </Panel>
    )
  }

  if (displayed.length === 0) {
    return (
      <Panel onOpenTrash={() => setTrashOpen(true)}>
        <div className="flex flex-1 flex-col items-center justify-center gap-3 p-4 text-center">
          <p className="text-sm text-muted-foreground">
            Noch keine Layer in diesem Projekt.
          </p>
          {/*
           * Wraps, and the buttons stretch to the full width once they do. Three of them
           * side by side need more room than this panel has at its usual width -- they
           * ran out past both edges, the outer two cut off mid-word. The panel is
           * resizable down to a narrow strip, so no fixed row of three can hold.
           */}
          <div className="flex w-full flex-wrap justify-center gap-2 *:min-w-0 *:flex-1 *:basis-40">
            <Button variant="outline" size="sm" onClick={onImportClick}>
              <Upload className="size-3.5" />
              Daten importieren
            </Button>
            <Button variant="outline" size="sm" onClick={onCreateLayerClick}>
              <Plus className="size-3.5" />
              Neuer Layer
            </Button>
            <Button variant="outline" size="sm" onClick={onGeoportalClick}>
              <Globe className="size-3.5" />
              Geoportal Hamburg
            </Button>
            <Button variant="outline" size="sm" onClick={onAddMapImageClick}>
              <ImageIcon className="size-3.5" />
              Eigener WMS-Dienst
            </Button>
          </div>
        </div>
        {trashDialog}
      </Panel>
    )
  }

  return (
    <Panel count={displayed.length} onOpenTrash={() => setTrashOpen(true)}>
      <ScrollArea className="flex-1">
        <ul
          className="p-1"
          // Without a dragover handler that cancels the event the browser refuses the
          // drop outright, so the list below the last row has to accept it too.
          onDragOver={(event) => event.preventDefault()}
          onDrop={() => dropBefore !== null && handleDrop(dropBefore)}
        >
          {displayed.map((layer, index) => (
            <LayerRow
              key={layer.id}
              layer={layer}
              projectId={projectId}
              isActive={layer.id === activeLayerId}
              isDragged={layer.id === draggedId}
              dropIndicator={
                dropBefore === index ? 'above' : dropBefore === index + 1 ? 'below' : null
              }
              canMoveUp={index > 0}
              canMoveDown={index < displayed.length - 1}
              isSnapSource={snapSources?.includes(layer.id) ?? false}
              // A Kartenbild has no geometry, so it can never serve as a snap source.
              showSnapToggle={snapSources !== null && layer.id !== activeLayerId && isVectorLayer(layer)}
              onToggleSnapSource={() => onToggleSnapSource?.(layer.id)}
              onSelect={() => onSelectLayer(layer.id)}
              onZoom={() => {
                if (!layer.extent) {
                  toast.info(`„${layer.name}" enthält keine Objekte`)
                  return
                }
                onZoomToLayer(layer.extent)
              }}
              onRename={() => setRenaming(layer)}
              onManageFields={() => setManagingFields(layer)}
              onSetBasemap={() => setSettingBasemap(layer)}
              onDelete={() => setDeleting(layer)}
              onMoveUp={() => move(layer, -1)}
              onMoveDown={() => move(layer, 1)}
              onDragStart={() => setDraggedId(layer.id)}
              onDragEnd={() => {
                setDraggedId(null)
                setDropBefore(null)
              }}
              onDragOverHalf={(half) => setDropBefore(half === 'top' ? index : index + 1)}
            />
          ))}
        </ul>
      </ScrollArea>

      <RenameLayerDialog layer={renaming} projectId={projectId} onOpenChange={() => setRenaming(null)} />
      <ManageFieldsDialog
        layer={managingFields}
        projectId={projectId}
        onOpenChange={() => setManagingFields(null)}
      />
      <LayerBasemapDialog
        layer={settingBasemap}
        projectId={projectId}
        onOpenChange={() => setSettingBasemap(null)}
      />
      <DeleteLayerDialog
        layer={deleting}
        projectId={projectId}
        onOpenChange={() => setDeleting(null)}
        onDeleted={(deletedId) => {
          if (deletedId === activeLayerId) onSelectLayer(null)
        }}
      />
      {trashDialog}
    </Panel>
  )
}

function Panel({
  children,
  count,
  onOpenTrash,
}: {
  children: React.ReactNode
  count?: number
  onOpenTrash: () => void
}) {
  return (
    <div className="flex h-full flex-col">
      <div className="flex h-7 shrink-0 items-center gap-2 border-b bg-muted/40 px-2 text-xs font-medium tracking-wide uppercase text-muted-foreground">
        <span>Layer</span>
        {count !== undefined && <span className="tabular-nums">{count}</span>}
        <Tooltip>
          <TooltipTrigger
            render={
              <Button
                variant="ghost"
                size="icon-sm"
                className="ml-auto size-5 shrink-0"
                onClick={onOpenTrash}
                aria-label="Papierkorb öffnen"
              >
                <Trash2 className="size-3.5" />
              </Button>
            }
          />
          <TooltipContent>Papierkorb</TooltipContent>
        </Tooltip>
      </div>
      {children}
    </div>
  )
}

interface LayerRowProps {
  layer: LayerSummary
  projectId: string
  isActive: boolean
  isSnapSource: boolean
  showSnapToggle: boolean
  onToggleSnapSource: () => void
  isDragged: boolean
  dropIndicator: 'above' | 'below' | null
  canMoveUp: boolean
  canMoveDown: boolean
  onSelect: () => void
  onZoom: () => void
  onRename: () => void
  onManageFields: () => void
  onSetBasemap: () => void
  onDelete: () => void
  onMoveUp: () => void
  onMoveDown: () => void
  onDragStart: () => void
  onDragEnd: () => void
  onDragOverHalf: (half: 'top' | 'bottom') => void
}

function LayerRow({
  layer,
  projectId,
  isActive,
  isSnapSource,
  showSnapToggle,
  onToggleSnapSource,
  isDragged,
  dropIndicator,
  canMoveUp,
  canMoveDown,
  onSelect,
  onZoom,
  onRename,
  onManageFields,
  onSetBasemap,
  onDelete,
  onMoveUp,
  onMoveDown,
  onDragStart,
  onDragEnd,
  onDragOverHalf,
}: LayerRowProps) {
  const updateLayer = useUpdateLayer(layer.id, projectId)
  const isVector = isVectorLayer(layer)
  // GEOMETRY_ICONS is only defined for a real geometry -- a Kartenbild gets its own
  // icon instead of a lookup that would otherwise need a null key (GEOMETRY_ICONS ab
  // Zeile 74 is the pattern this follows).
  const Icon = isVector ? GEOMETRY_ICONS[layer.geometryType] : ImageIcon
  // A Kartenbild has no fill/line/marker colour to preview -- null reads as the neutral
  // muted-foreground icon, the same as a vector layer that has never been styled.
  const previewColor = isVector ? previewColorOf(layer.style) : null
  const clipMaskLocked = isVector ? clipMaskLockedReason(layer.geometryType) : null
  const ClipModeIcon = layer.clipMode != null ? CLIP_MODE_ICONS[layer.clipMode] : null

  function handleClipModeChange(mode: ClipMode | null) {
    // Setting a mode here never touches another layer's `clipMode` -- a project can
    // hold any number of masks at once (contract phase 21), so there is no other row
    // to report on and `useUpdateLayer`'s own invalidation is enough to keep the tree
    // in line.
    updateLayer.mutate(
      { clipMode: mode },
      { onError: () => toast.error('Das Programm konnte den Zuschnitt nicht ändern') },
    )
  }

  // Two instances so the spinner lands on the entry the user actually clicked -- not for
  // independent disabling, which is the opposite of what `isExporting` below does.
  const exportLayerMutation = useExportLayer()
  const exportSelectionMutation = useExportLayer()
  // Deliberately shared, not per-entry: the backend export pool has four concurrent
  // slots and a queue of eight. Two exports fired from the same row would tie up two of
  // those slots for what the pool sees as the same click, so both entries disable
  // together while either request is in flight.
  const isExporting = exportLayerMutation.isPending || exportSelectionMutation.isPending
  const canExportSelection = useSelection(
    (state) => state.layerId === layer.id && state.selected.size > 0,
  )

  function handleExportLayer() {
    exportLayerMutation.mutate(
      { layerId: layer.id },
      { onError: (caught) => toast.error(exportErrorMessage(caught)) },
    )
  }

  function handleExportSelection() {
    // Re-read the store instead of trusting `canExportSelection`: the menu item's
    // disabled state can lag a beat behind a selection change elsewhere, and sending an
    // empty selection downloads an empty file where the user expects the selected data.
    const { layerId: selectedLayerId, selected } = useSelection.getState()
    if (selectedLayerId !== layer.id || selected.size === 0) return
    exportSelectionMutation.mutate(
      { layerId: layer.id, fids: Array.from(selected) },
      { onError: (caught) => toast.error(exportErrorMessage(caught)) },
    )
  }

  return (
    <li
      draggable
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
      onDragOver={(event) => {
        event.preventDefault()
        const box = event.currentTarget.getBoundingClientRect()
        onDragOverHalf(event.clientY < box.top + box.height / 2 ? 'top' : 'bottom')
      }}
      className={cn(
        'group flex items-center gap-1.5 rounded px-1.5 py-1',
        // A plain border would resize the row and make the whole list twitch during a
        // drag, so the drop indicator is drawn as an inset shadow instead.
        dropIndicator === 'above' && 'shadow-[inset_0_1px_0_0_var(--color-foreground)]',
        dropIndicator === 'below' && 'shadow-[inset_0_-1px_0_0_var(--color-foreground)]',
        isDragged && 'opacity-40',
        isActive ? 'bg-accent' : 'hover:bg-accent/50',
      )}
    >
      <Checkbox
        checked={layer.visible}
        onCheckedChange={(checked) => updateLayer.mutate({ visible: checked === true })}
        aria-label={`${layer.name} ${layer.visible ? 'ausblenden' : 'einblenden'}`}
        className="shrink-0"
      />

      {/* The row is a button so it is reachable by keyboard; drag and drop is not, which
          is why the menu carries "nach oben"/"nach unten" as an equal path. */}
      <button
        type="button"
        onClick={onSelect}
        onDoubleClick={onZoom}
        // overflow-hidden so what does not fit is cut off here rather than running on
        // over the buttons to the right of it.
        className="flex min-w-0 flex-1 items-center gap-1.5 overflow-hidden text-left"
        title={
          isVector
            ? `${layer.name} (${GEOMETRY_LABELS[layer.geometryType]}, ${formatCount(layer.featureCount)} Objekte)`
            : `${layer.name} (Kartenbild)`
        }
      >
        {/* Filled, not outlined: an outlined square sitting next to the visibility
            checkbox reads as a second, unticked checkbox. Filled it reads as what it
            is -- a symbol preview, the same slot QGIS uses for the layer's styling. */}
        <Icon
          className={cn(
            'size-3 shrink-0',
            layer.geometryType !== 'MULTILINESTRING' && 'fill-current',
            previewColor === null && 'text-muted-foreground',
          )}
          style={previewColor === null ? undefined : { color: previewColor }}
        />
        {/* Keeps a stub of the name at every panel width. Free to shrink it went to zero
            in a narrow dock and the row named no layer at all -- the object count, which
            never gives way on its own, had taken the last of the space. A cut-off word
            says more than none, so the count is the one that yields now. */}
        <span className={cn('min-w-8 truncate', !layer.visible && 'text-muted-foreground')}>
          {layer.name}
        </span>
        {/* A Kartenbild's featureCount is always 0 (contract "wird nicht angezeigt") --
            showing it would read as an empty layer rather than as one with no concept
            of objects at all. The slot the count would occupy carries the zoom-window
            badge instead. */}
        {isVector ? (
          <span className="ml-auto min-w-0 truncate text-xs text-muted-foreground tabular-nums">
            {formatCount(layer.featureCount)}
          </span>
        ) : (
          <ZoomWindowBadge minZoom={layer.minZoom} maxZoom={layer.maxZoom} />
        )}
      </button>

      {/* Kept outside the button: `layer.visible` never dims it, because the mask goes
          on clipping everything above it while switched off (contract "Eine unsichtbar
          geschaltete Maske wirkt weiter"). A badge that faded with the checkbox would
          hide the one fact the user needs to explain the cut. */}
      {layer.clipMode != null && ClipModeIcon && (
        <Tooltip>
          <TooltipTrigger
            render={
              <span
                tabIndex={0}
                className="shrink-0 text-foreground"
                aria-label={clipMaskBadgeAriaLabel(layer.clipMode)}
              >
                <ClipModeIcon className="size-3" />
              </span>
            }
          />
          <TooltipContent className="max-w-xs">{clipMaskBadgeTooltip(layer.clipMode)}</TooltipContent>
        </Tooltip>
      )}

      {/* Secondary, so muted rather than full-strength -- unlike the mask badge above,
          missing this one costs nothing but a bit of context. Says "Masken", plural
          and without an article, because any number of layers below this one in the
          tree could be marking it -- contract phase 21 drops the old one-mask-per-
          project limit that let this name "the" mask. */}
      {layer.clipMode == null && (layer.clipVersion ?? 0) > 0 && (
        <Tooltip>
          <TooltipTrigger
            render={
              <span
                tabIndex={0}
                className="shrink-0 text-muted-foreground"
                aria-label="Wird durch Masken zugeschnitten"
              >
                <Crop className="size-3" />
              </span>
            }
          />
          <TooltipContent className="max-w-xs">Wird durch Masken zugeschnitten</TooltipContent>
        </Tooltip>
      )}

      {/* Only while editing, and never on the layer being edited -- that one is always a
          snap source, and offering to switch it off would suggest otherwise. */}
      {showSnapToggle && (
        <Button
          variant="ghost"
          size="icon-sm"
          className={cn(
            'size-5 shrink-0',
            isSnapSource ? 'text-foreground' : 'text-muted-foreground/40 hover:text-muted-foreground',
          )}
          aria-label={`${layer.name} ${isSnapSource ? 'nicht mehr' : ''} als Fangquelle verwenden`}
          aria-pressed={isSnapSource}
          onClick={(event) => {
            event.stopPropagation()
            onToggleSnapSource()
          }}
        >
          <Magnet className="size-3" />
        </Button>
      )}

      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="ghost"
              size="icon-sm"
              className="size-5 shrink-0 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 data-[popup-open]:opacity-100"
              aria-label={`Aktionen für ${layer.name}`}
            >
              <MoreHorizontal className="size-3.5" />
            </Button>
          }
        />
        <DropdownMenuContent align="end">
          <DropdownMenuItem onClick={onZoom}>
            <Crosshair className="size-3.5" />
            Auf Layer zoomen
          </DropdownMenuItem>
          <DropdownMenuItem onClick={onRename}>
            <Pencil className="size-3.5" />
            Umbenennen
          </DropdownMenuItem>
          {/* A Kartenbild has no attributes, no mask geometry and nothing to export --
              only the actions that make sense for a picture stay (message "Im
              Aktionsmenü fallen weg: Felder verwalten, Als Maske, Exportieren"). */}
          {isVector && (
            <DropdownMenuItem onClick={onManageFields}>
              <Columns3 className="size-3.5" />
              Felder verwalten
            </DropdownMenuItem>
          )}
          <DropdownMenuItem onClick={onSetBasemap}>
            <MapIcon className="size-3.5" />
            Hintergrundkarte
          </DropdownMenuItem>
          {isVector && (
            <>
              {/* Shown for every geometry type, disabled with a reason for the two that
                  cannot hold a mask -- contract "erscheint gesperrt mit dem Grund, nicht
                  wortlos". A submenu, not a checkbox item: this is a choice between five
                  states (no clip, plus the four modes from CONTRACT.md phase 21), not a
                  single flag to toggle (contract "keine Ja/Nein-Marke mehr"). */}
              <DropdownMenuSub>
                <DropdownMenuSubTrigger disabled={clipMaskLocked !== null}>
                  <Scissors className="size-3.5" />
                  {clipMaskLocked ? (
                    <span className="flex flex-col">
                      <span>Zuschnitt für alles darüber</span>
                      <span className="text-xs text-muted-foreground">{clipMaskLocked}</span>
                    </span>
                  ) : (
                    'Zuschnitt für alles darüber'
                  )}
                </DropdownMenuSubTrigger>
                <DropdownMenuSubContent>
                  {/* A `DropdownMenuRadioGroup`, not a plain list of items: the five choices
                      are mutually exclusive, the same role radio buttons play in a form. */}
                  <DropdownMenuRadioGroup
                    value={layer.clipMode ?? null}
                    onValueChange={(value) => handleClipModeChange(value as ClipMode | null)}
                  >
                    {availableClipModes(layer.geometryType).map((mode) => (
                      <DropdownMenuRadioItem key={mode ?? 'none'} value={mode}>
                        {clipModeLabel(mode)}
                      </DropdownMenuRadioItem>
                    ))}
                  </DropdownMenuRadioGroup>
                </DropdownMenuSubContent>
              </DropdownMenuSub>
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={handleExportLayer} disabled={isExporting}>
                {exportLayerMutation.isPending ? (
                  <Loader2 className="size-3.5 animate-spin" />
                ) : (
                  <Download className="size-3.5" />
                )}
                {exportLayerMutation.isPending ? 'Wird exportiert…' : 'Layer exportieren'}
              </DropdownMenuItem>
              <DropdownMenuItem
                onClick={handleExportSelection}
                disabled={!canExportSelection || isExporting}
              >
                {exportSelectionMutation.isPending ? (
                  <Loader2 className="size-3.5 animate-spin" />
                ) : (
                  <FileDown className="size-3.5" />
                )}
                {exportSelectionMutation.isPending ? 'Wird exportiert…' : 'Auswahl exportieren'}
              </DropdownMenuItem>
            </>
          )}
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={onMoveUp} disabled={!canMoveUp}>
            <ChevronUp className="size-3.5" />
            Nach oben
          </DropdownMenuItem>
          <DropdownMenuItem onClick={onMoveDown} disabled={!canMoveDown}>
            <ChevronDown className="size-3.5" />
            Nach unten
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem variant="destructive" onClick={onDelete}>
            <Trash2 className="size-3.5" />
            Löschen
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </li>
  )
}

/**
 * Whether this Kartenbild is being drawn at the zoom the map is standing at.
 *
 * Only for map images, and only because they are the kind that arrives with a zoom
 * window it did not ask for: a WMS service declares the scale range each of its layers
 * draws in, and roughly a third of Hamburg's services do (10 of 30 measured). A layer
 * outside that range is not on the map, while its entry here sits with its tick set --
 * which reads as a broken import rather than as a property of the service.
 *
 * Shown in both states rather than only the bad one: a badge that appears out of nowhere
 * is a warning, and a layer that simply has a scale range is not a problem. Muted while
 * everything is fine, plain while it is not.
 *
 * Deliberately not a button. The tick beside the name is what switches a layer on and
 * off; a second control that looked like one but only reported would be two switches for
 * one fact.
 */
function ZoomWindowBadge({ minZoom, maxZoom }: { minZoom: number; maxZoom: number }) {
  const currentZoom = useMapViewport((state) => state.zoom)
  const state = zoomWindowState(minZoom, maxZoom, currentZoom)
  if (state === 'unknown') return null

  const outside = state !== 'inside'
  const Icon = outside ? EyeOff : Eye
  return (
    <span
      className="ml-auto flex shrink-0 items-center"
      // A title, not a tooltip component: the row is already a button with its own
      // title, and nesting an interactive tooltip inside it would steal the click.
      title={describeZoomWindow(minZoom, maxZoom, currentZoom) ?? 'Wird bei diesem Zoom gezeichnet'}
    >
      <Icon className={cn('size-3.5', outside ? 'text-foreground' : 'text-muted-foreground/50')} />
    </span>
  )
}
