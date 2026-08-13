import {
  Check,
  Circle,
  Magnet,
  MousePointer2,
  Pencil,
  Redo2,
  Spline,
  Square,
  Trash2,
  Undo2,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { formatCount } from '@/lib/format'
import type { GeometryType } from '@/api/layers'
import { countChanges, useEditing } from '@/state/editing'
import { toolsFor, type DrawTool } from './drawTools'

interface ToolAppearance {
  label: string
  icon: typeof Square
  /** Overrides the shared icon size; only the point needs it, see below. */
  iconClassName?: string
}

const TOOL_LABELS: Record<DrawTool, ToolAppearance> = {
  select: { label: 'Auswählen und Stützpunkte verschieben', icon: MousePointer2 },
  // Filled and smaller than the outline icons beside it, which is what makes it read as a
  // point rather than as a circle -- `Circle` at the shared size is the shape the layer
  // tree uses for a point *layer*, and the tool draws one point, not a ring.
  point: { label: 'Punkt zeichnen', icon: Circle, iconClassName: 'size-2.5 fill-current' },
  linestring: { label: 'Linie zeichnen', icon: Spline },
  polygon: { label: 'Fläche zeichnen', icon: Square },
}

const ALL_TOOLS: DrawTool[] = ['select', 'point', 'linestring', 'polygon']

interface EditToolbarProps {
  active: boolean
  geometryType?: GeometryType
  tool: DrawTool
  onToolChange: (tool: DrawTool) => void
  onStart: () => void
  onSave: () => void
  onDiscard: () => void
  /**
   * Leaves the drawing mode. Left to the caller (the workspace route) because a session
   * that still holds unsaved changes has to be confirmed first, and the wording of that
   * question lives with the other discard prompts -- same reasoning as `onStart`.
   */
  onLeave: () => void
  /** Deletes the currently selected feature; no-op when nothing is selected. */
  onDelete: () => void
  /** Whether a feature is selected and can be deleted. */
  canDelete: boolean
  isSaving: boolean
  canEdit: boolean
  snapEnabled: boolean
  onToggleSnap: () => void
  /** Set when the viewport holds more features than the editor loads; snapping is blind there. */
  snapUnavailableReason?: string
}

export function EditToolbar({
  active,
  geometryType,
  tool,
  onToolChange,
  onStart,
  onSave,
  onDiscard,
  onLeave,
  onDelete,
  canDelete,
  isSaving,
  canEdit,
  snapEnabled,
  onToggleSnap,
  snapUnavailableReason,
}: EditToolbarProps) {
  const buffer = useEditing((state) => state.buffer)
  const undo = useEditing((state) => state.undo)
  const redo = useEditing((state) => state.redo)
  const canUndo = useEditing((state) => state.undoStack.length > 0)
  const canRedo = useEditing((state) => state.redoStack.length > 0)
  const pending = countChanges(buffer)

  if (!active) {
    return (
      <Tooltip>
        <TooltipTrigger
          render={
            <Button variant="outline" size="sm" onClick={onStart} disabled={!canEdit}>
              <Pencil className="size-3.5" />
              Bearbeiten
            </Button>
          }
        />
        <TooltipContent>
          {canEdit ? 'Zeichenmodus starten' : 'Wählen Sie zuerst einen Layer im Layerbaum aus'}
        </TooltipContent>
      </Tooltip>
    )
  }

  const allowed = geometryType ? toolsFor(geometryType) : ALL_TOOLS

  return (
    <div className="flex items-center gap-1">
      {ALL_TOOLS.map((entry) => {
        const { label, icon: Icon, iconClassName } = TOOL_LABELS[entry]
        const usable = allowed.includes(entry)
        return (
          <Tooltip key={entry}>
            <TooltipTrigger
              render={
                <Button
                  variant={tool === entry ? 'secondary' : 'ghost'}
                  size="icon-sm"
                  className={cn('size-7', tool === entry && 'ring-1 ring-border')}
                  disabled={!usable}
                  aria-label={label}
                  aria-pressed={tool === entry}
                  onClick={() => onToolChange(entry)}
                >
                  <Icon className={cn('size-3.5', iconClassName)} />
                </Button>
              }
            />
            <TooltipContent>
              {/* Disabled rather than hidden, and the reason is in the tooltip: a tool
                  that silently disappears looks like a missing feature. */}
              {usable
                ? label
                : `${label}. Dieser Layer erlaubt nur ${geometryDescription(geometryType)}.`}
            </TooltipContent>
          </Tooltip>
        )
      })}

      <Separator orientation="vertical" className="mx-1 h-4 data-vertical:self-center" />

      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant={snapEnabled && !snapUnavailableReason ? 'secondary' : 'ghost'}
              size="icon-sm"
              className={cn('size-7', snapEnabled && !snapUnavailableReason && 'ring-1 ring-border')}
              disabled={Boolean(snapUnavailableReason)}
              aria-label="Einrasten an vorhandenen Geometrien"
              aria-pressed={snapEnabled && !snapUnavailableReason}
              onClick={onToggleSnap}
            >
              <Magnet className="size-3.5" />
            </Button>
          }
        />
        <TooltipContent className="max-w-xs">
          {/* Stated rather than silently switched off: snapping that stops working without
              a word is how imprecise geometry gets drawn without anyone noticing. */}
          {snapUnavailableReason ??
            'Einrasten an vorhandenen Objekten: zuerst Stützpunkte, dann Schnittpunkte, dann Kanten.'}
        </TooltipContent>
      </Tooltip>

      <Separator orientation="vertical" className="mx-1 h-4 data-vertical:self-center" />

      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant="ghost"
              size="icon-sm"
              className="size-7"
              disabled={!canDelete || isSaving}
              aria-label="Ausgewähltes Objekt löschen"
              onClick={onDelete}
            >
              <Trash2 className="size-3.5" />
            </Button>
          }
        />
        <TooltipContent>
          {canDelete
            ? 'Ausgewähltes Objekt löschen (auch Entf)'
            : 'Objekt in der Karte auswählen, um es zu löschen'}
        </TooltipContent>
      </Tooltip>

      <Button
        variant="ghost"
        size="icon-sm"
        className="size-7"
        disabled={!canUndo}
        onClick={undo}
        aria-label="Rückgängig"
      >
        <Undo2 className="size-3.5" />
      </Button>
      <Button
        variant="ghost"
        size="icon-sm"
        className="size-7"
        disabled={!canRedo}
        onClick={redo}
        aria-label="Wiederherstellen"
      >
        <Redo2 className="size-3.5" />
      </Button>

      <Separator orientation="vertical" className="mx-1 h-4 data-vertical:self-center" />

      {/* Permanently visible, not only when non-zero: the whole point is that the number
          of unsaved changes never comes as a surprise (plan section D.8). */}
      <span className="text-xs text-muted-foreground tabular-nums">
        {pending === 0 ? 'keine Änderungen' : `${formatCount(pending)} ungespeichert`}
      </span>

      {/* Disabled with an empty buffer, matching the table's toolbar: an enabled button
          that does nothing visible is what made this one look like the way out of the
          mode, which it never was. Leaving is the X beside it. */}
      <Button
        variant="ghost"
        size="sm"
        onClick={onDiscard}
        disabled={pending === 0 || isSaving}
      >
        Verwerfen
      </Button>
      <Button size="sm" onClick={onSave} disabled={pending === 0 || isSaving}>
        <Check className="size-3.5" />
        {isSaving ? 'Wird gespeichert…' : 'Speichern'}
      </Button>

      <Separator orientation="vertical" className="mx-1 h-4 data-vertical:self-center" />

      {/* The way out, same button in the same place as in the table's toolbar and the
          measurement toolbar. Without it the drawing mode could only be left by saving,
          by switching layers, or by leaving the page. */}
      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant="ghost"
              size="icon-sm"
              className="size-7"
              disabled={isSaving}
              aria-label="Zeichenmodus verlassen"
              onClick={onLeave}
            >
              <X className="size-3.5" />
            </Button>
          }
        />
        <TooltipContent>Zeichenmodus verlassen</TooltipContent>
      </Tooltip>
    </div>
  )
}

function geometryDescription(geometryType?: GeometryType): string {
  switch (geometryType) {
    case 'MULTIPOINT':
      return 'Punkte'
    case 'MULTILINESTRING':
      return 'Linien'
    case 'MULTIPOLYGON':
      return 'Flächen'
    default:
      return 'Geometrien'
  }
}
