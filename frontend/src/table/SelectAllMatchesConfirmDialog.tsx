import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { formatCount } from '@/lib/format'

interface SelectAllMatchesConfirmDialogProps {
  totalCount: number
  onConfirm: () => void
  onCancel: () => void
}

/**
 * Gate before replacing the selection with every filter/search match -- see
 * `needsSelectAllConfirmation` in `selectAllMatches.ts`.
 *
 * Same pattern as `RectangleSelectConfirmDialog` (Phase 9): the count is stated
 * outright, because it is the only thing that conveys what "select all" is about to
 * do. Its own component rather than a reuse of that dialog, because the wording is
 * about a rectangle drawn on the map -- wrong for a filter or search restriction.
 */
export function SelectAllMatchesConfirmDialog({
  totalCount,
  onConfirm,
  onCancel,
}: SelectAllMatchesConfirmDialogProps) {
  return (
    <AlertDialog open onOpenChange={(next) => !next && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Große Auswahl</AlertDialogTitle>
          <AlertDialogDescription>
            Die Einschränkung ergibt {formatCount(totalCount)} Treffer. Alle auswählen?
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={onCancel}>Abbrechen</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm}>Alle auswählen</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
