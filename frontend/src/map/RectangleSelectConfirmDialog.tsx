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

interface RectangleSelectConfirmDialogProps {
  totalCount: number
  onConfirm: () => void
  onCancel: () => void
}

/**
 * Gate before loading a large rectangle selection -- see `needsConfirmation` in
 * `rectangleSelectPaging`.
 *
 * Modeled on `DeleteLayerDialog`: the count is stated outright, because it is the only
 * thing that conveys what "select all" is about to cost.
 */
export function RectangleSelectConfirmDialog({
  totalCount,
  onConfirm,
  onCancel,
}: RectangleSelectConfirmDialogProps) {
  return (
    <AlertDialog open onOpenChange={(next) => !next && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Große Auswahl</AlertDialogTitle>
          <AlertDialogDescription>
            Das Rechteck enthält {formatCount(totalCount)} Objekte. Alle auswählen?
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
