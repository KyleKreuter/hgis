import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { formatCount } from '@/lib/format'
import { DiscardEditsDialog } from './DiscardEditsDialog'
import type { TableEditToolbarState } from './useTableEditToolbar'

interface TableEditToolbarExitProps {
  toolbar: TableEditToolbarState
}

/**
 * The way out of the table's edit mode -- kept in its own component, rendered as a
 * `shrink-0` sibling *next to* `Panel`'s scrollable toolbar strip (AttributeTable.tsx),
 * not inside it.
 *
 * `position: sticky` was tried first and rejected: a sticky element does not reserve
 * its own space, it only changes where it renders once its normal-flow position would
 * scroll past the anchor -- so at rest (`scrollLeft: 0`, no scrolling done at all) it
 * rendered directly on top of whatever else happened to occupy that same stretch of
 * the strip, which at 340-400px was "Speichern". A real mouse click there opened the
 * leave-mode confirm dialog instead of saving. A dedicated region with its own width
 * has nothing to overlap, because nothing else is ever drawn there.
 */
export function TableEditToolbarExit({ toolbar }: TableEditToolbarExitProps) {
  if (!toolbar.active) return null

  return (
    <>
      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant="ghost"
              size="icon-sm"
              className="size-7"
              disabled={toolbar.isSaving}
              aria-label="Bearbeitungsmodus verlassen"
              onClick={toolbar.requestLeave}
            >
              <X className="size-3.5" />
            </Button>
          }
        />
        <TooltipContent>Bearbeitungsmodus verlassen</TooltipContent>
      </Tooltip>

      <DiscardEditsDialog
        open={toolbar.confirmLeave}
        title="Bearbeitungsmodus verlassen?"
        description={`Es gibt ${formatCount(toolbar.pending)} ungespeicherte Änderungen in der Tabelle. Sie gehen verloren.`}
        confirmLabel="Änderungen verwerfen"
        onConfirm={toolbar.confirmDiscardAndLeave}
        onCancel={toolbar.cancelLeave}
      />
    </>
  )
}
