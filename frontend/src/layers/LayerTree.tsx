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
  FileDown,
  Loader2,
  Magnet,
  Map as MapIcon,
  MoreHorizontal,
  Pencil,
  Plus,
  Scissors,
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
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Skeleton } from '@/components/ui/skeleton'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { formatCount } from '@/lib/format'
import { exportErrorMessage, useExportLayer } from '@/api/export'
import {
  layerListQuery,
  useReorderLayers,
  useUpdateLayer,
  type GeometryType,
  type LayerSummary,
} from '@/api/layers'
import { useSelection } from '@/state/selection'
import { previewColorOf } from '@/styling'
import { clipMaskLockedReason, clipMaskReplacedMessage, findOtherClipMask } from './clipMask'
import { DeleteLayerDialog } from './DeleteLayerDialog'
import { GEOMETRY_LABELS } from './geometry'
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

interface LayerTreeProps {
  projectId: string
  activeLayerId: string | null
  onSelectLayer: (layerId: string | null) => void
  onZoomToLayer: (extent: [number, number, number, number]) => void
  onImportClick: () => void
  onCreateLayerClick: () => void
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
  snapSources = null,
  onToggleSnapSource,
}: LayerTreeProps) {
  const { data: layers, isPending } = useQuery(layerListQuery(projectId))
  const reorder = useReorderLayers(projectId)

  const [renaming, setRenaming] = useState<LayerSummary | null>(null)
  const [managingFields, setManagingFields] = useState<LayerSummary | null>(null)
  const [settingBasemap, setSettingBasemap] = useState<LayerSummary | null>(null)
  const [deleting, setDeleting] = useState<LayerSummary | null>(null)
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

  if (isPending) {
    return (
      <Panel>
        <div className="grid gap-1.5 p-2">
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-4/5" />
        </div>
      </Panel>
    )
  }

  if (displayed.length === 0) {
    return (
      <Panel>
        <div className="flex flex-1 flex-col items-center justify-center gap-3 p-6 text-center">
          <p className="text-sm text-muted-foreground">
            Noch keine Layer in diesem Projekt.
          </p>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={onImportClick}>
              <Upload className="size-3.5" />
              Daten importieren
            </Button>
            <Button variant="outline" size="sm" onClick={onCreateLayerClick}>
              <Plus className="size-3.5" />
              Neuer Layer
            </Button>
          </div>
        </div>
      </Panel>
    )
  }

  return (
    <Panel count={displayed.length}>
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
              otherClipMask={findOtherClipMask(displayed, layer.id)}
              isActive={layer.id === activeLayerId}
              isDragged={layer.id === draggedId}
              dropIndicator={
                dropBefore === index ? 'above' : dropBefore === index + 1 ? 'below' : null
              }
              canMoveUp={index > 0}
              canMoveDown={index < displayed.length - 1}
              isSnapSource={snapSources?.includes(layer.id) ?? false}
              showSnapToggle={snapSources !== null && layer.id !== activeLayerId}
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
    </Panel>
  )
}

function Panel({ children, count }: { children: React.ReactNode; count?: number }) {
  return (
    <div className="flex h-full flex-col">
      <div className="flex h-7 shrink-0 items-center gap-2 border-b bg-muted/40 px-2 text-xs font-medium tracking-wide uppercase text-muted-foreground">
        <span>Layer</span>
        {count !== undefined && <span className="tabular-nums">{count}</span>}
      </div>
      {children}
    </div>
  )
}

interface LayerRowProps {
  layer: LayerSummary
  projectId: string
  /** The project's mask, if it is a *different* layer than this row -- null otherwise. */
  otherClipMask: LayerSummary | null
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
  otherClipMask,
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
  const Icon = GEOMETRY_ICONS[layer.geometryType]
  const previewColor = previewColorOf(layer.style)
  const clipMaskLocked = clipMaskLockedReason(layer.geometryType)

  function handleToggleClipMask(next: boolean) {
    updateLayer.mutate(
      { isClipMask: next },
      {
        // Derived from the client's own list, not from the response: the server only
        // describes the layer it just patched, and the invariant "at most one mask per
        // project" already tells us who lost the role (contract "Höchstens eine Maske
        // je Projekt"). `useUpdateLayer`'s own invalidation brings that other row's
        // `isClipMask` back in line right after.
        onSuccess: () => {
          if (next && otherClipMask) toast.info(clipMaskReplacedMessage(otherClipMask))
        },
        onError: () => toast.error('Das Programm konnte die Maske nicht ändern'),
      },
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
        className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
        title={`${layer.name} (${GEOMETRY_LABELS[layer.geometryType]}, ${formatCount(layer.featureCount)} Objekte)`}
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
        <span className={cn('truncate', !layer.visible && 'text-muted-foreground')}>
          {layer.name}
        </span>
        <span className="ml-auto shrink-0 text-xs text-muted-foreground tabular-nums">
          {formatCount(layer.featureCount)}
        </span>
      </button>

      {/* Kept outside the button: `layer.visible` never dims it, because the mask goes
          on clipping everything above it while switched off (contract "Eine unsichtbar
          geschaltete Maske wirkt weiter"). A badge that faded with the checkbox would
          hide the one fact the user needs to explain the cut. */}
      {layer.isClipMask && (
        <Tooltip>
          <TooltipTrigger
            render={
              <span tabIndex={0} className="shrink-0 text-foreground" aria-label="Maske für den Zuschnitt">
                <Scissors className="size-3" />
              </span>
            }
          />
          <TooltipContent className="max-w-xs">
            Maske für den Zuschnitt. Wirkt auch, wenn der Layer ausgeblendet ist.
          </TooltipContent>
        </Tooltip>
      )}

      {/* Secondary, so muted rather than full-strength -- unlike the mask badge above,
          missing this one costs nothing but a bit of context. */}
      {!layer.isClipMask && (layer.clipVersion ?? 0) > 0 && (
        <Tooltip>
          <TooltipTrigger
            render={
              <span
                tabIndex={0}
                className="shrink-0 text-muted-foreground"
                aria-label="Wird durch die Maske zugeschnitten"
              >
                <Crop className="size-3" />
              </span>
            }
          />
          <TooltipContent className="max-w-xs">Wird durch die Maske zugeschnitten</TooltipContent>
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
          <DropdownMenuItem onClick={onManageFields}>
            <Columns3 className="size-3.5" />
            Felder verwalten
          </DropdownMenuItem>
          <DropdownMenuItem onClick={onSetBasemap}>
            <MapIcon className="size-3.5" />
            Hintergrundkarte
          </DropdownMenuItem>
          {/* Shown for every geometry type, disabled with a reason for the two that
              cannot hold a mask -- contract "erscheint gesperrt mit dem Grund, nicht
              wortlos". A checkbox item, not a plain one: unlike every other entry here
              this one reflects a state (is this layer the mask right now?) and toggles
              it, the same role `Checkbox` plays for `visible` above. */}
          <DropdownMenuCheckboxItem
            checked={layer.isClipMask}
            disabled={clipMaskLocked !== null}
            onCheckedChange={handleToggleClipMask}
          >
            {clipMaskLocked ? (
              <span className="flex flex-col">
                <span>Als Zuschnitt für alles darüber</span>
                <span className="text-xs text-muted-foreground">{clipMaskLocked}</span>
              </span>
            ) : (
              'Als Zuschnitt für alles darüber'
            )}
          </DropdownMenuCheckboxItem>
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
