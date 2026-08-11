import { useState } from 'react'
import { Check, Pencil, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { formatCount } from '@/lib/format'
import { changeCount } from './tableEditSession'
import { useTableEditing } from './useTableEditing'
import { useSaveTableEdits } from './useSaveTableEdits'
import { DiscardEditsDialog } from './DiscardEditsDialog'

interface TableEditToolbarProps {
  layerId: string
  projectId: string
  /**
   * Starts table editing. Left to the caller (the workspace route) because starting it
   * may first have to end a dirty map-editing session -- a cross-mode decision this
   * toolbar has no business making (CONTRACT.md).
   */
  onRequestStart: () => void
}

/**
 * The table's own "Bearbeiten" switch plus, once on, the counter and
 * save/discard/leave actions -- modeled on `EditToolbar` (map editing) and
 * `MeasurementToolbar` (the leave-mode X button), so the same interaction shows up the
 * same way in both places.
 */
export function TableEditToolbar({ layerId, projectId, onRequestStart }: TableEditToolbarProps) {
  const active = useTableEditing((state) => state.active)
  const pending = useTableEditing((state) => changeCount(state))
  const { save, isSaving } = useSaveTableEdits(layerId, projectId)
  const [confirmLeave, setConfirmLeave] = useState(false)

  function requestLeave() {
    if (pending > 0) {
      setConfirmLeave(true)
      return
    }
    useTableEditing.getState().end()
  }

  if (!active) {
    return (
      <Button variant="outline" size="sm" onClick={onRequestStart}>
        <Pencil className="size-3.5" />
        Bearbeiten
      </Button>
    )
  }

  return (
    <div className="flex shrink-0 items-center gap-2">
      {/* Permanently visible, matching EditToolbar: the count of unsaved changes must
          never come as a surprise. */}
      <span className="text-xs text-muted-foreground tabular-nums">
        {pending === 0 ? 'keine Änderungen' : `${formatCount(pending)} ungespeichert`}
      </span>

      <Button
        variant="ghost"
        size="sm"
        onClick={() => useTableEditing.getState().reset()}
        disabled={pending === 0 || isSaving}
      >
        Verwerfen
      </Button>
      <Button size="sm" onClick={() => void save()} disabled={pending === 0 || isSaving}>
        <Check className="size-3.5" />
        {isSaving ? 'Wird gespeichert…' : 'Speichern'}
      </Button>

      <Separator orientation="vertical" className="h-4 data-vertical:self-center" />

      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant="ghost"
              size="icon-sm"
              className="size-7"
              disabled={isSaving}
              aria-label="Bearbeitungsmodus verlassen"
              onClick={requestLeave}
            >
              <X className="size-3.5" />
            </Button>
          }
        />
        <TooltipContent>Bearbeitungsmodus verlassen</TooltipContent>
      </Tooltip>

      <DiscardEditsDialog
        open={confirmLeave}
        title="Bearbeitungsmodus verlassen?"
        description={`Es gibt ${formatCount(pending)} ungespeicherte Änderungen in der Tabelle. Sie gehen verloren.`}
        confirmLabel="Änderungen verwerfen"
        onConfirm={() => {
          useTableEditing.getState().end()
          setConfirmLeave(false)
        }}
        onCancel={() => setConfirmLeave(false)}
      />
    </div>
  )
}
