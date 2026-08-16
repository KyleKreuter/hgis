import { Check, Pencil } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { formatCount } from '@/lib/format'
import type { TableEditToolbarState } from './useTableEditToolbar'

interface TableEditToolbarProps {
  toolbar: TableEditToolbarState
  /**
   * Starts table editing. Left to the caller (the workspace route) because starting it
   * may first have to end a dirty map-editing session -- a cross-mode decision this
   * toolbar has no business making (CONTRACT.md).
   */
  onRequestStart: () => void
}

/**
 * The table's own "Bearbeiten" switch plus, once on, the counter and save/discard
 * actions -- modeled on `EditToolbar` (map editing) and `MeasurementToolbar` (the
 * leave-mode X button), so the same interaction shows up the same way in both places.
 *
 * The leave button itself lives in `TableEditToolbarExit`, not here: this half sits
 * inside `Panel`'s scrollable strip (AttributeTable.tsx) and is free to scroll out of
 * view in a narrow dock, but the way out of edit mode must never be. Both halves share
 * one `useTableEditToolbar(layerId, projectId)` call, lifted to the caller, so there is
 * exactly one `isSaving` between them.
 */
export function TableEditToolbar({ toolbar, onRequestStart }: TableEditToolbarProps) {
  if (!toolbar.active) {
    return (
      <Button variant="outline" size="sm" onClick={onRequestStart}>
        <Pencil className="size-3.5" />
        Bearbeiten
      </Button>
    )
  }

  return (
    <>
      {/* Permanently visible, matching EditToolbar: the count of unsaved changes must
          never come as a surprise. */}
      <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
        {toolbar.pending === 0 ? 'keine Änderungen' : `${formatCount(toolbar.pending)} ungespeichert`}
      </span>

      <Button
        variant="ghost"
        size="sm"
        onClick={toolbar.reset}
        disabled={toolbar.pending === 0 || toolbar.isSaving}
      >
        Verwerfen
      </Button>
      <Button size="sm" onClick={() => void toolbar.save()} disabled={toolbar.pending === 0 || toolbar.isSaving}>
        <Check className="size-3.5" />
        {toolbar.isSaving ? 'Wird gespeichert…' : 'Speichern'}
      </Button>
    </>
  )
}
