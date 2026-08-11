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

interface DiscardEditsDialogProps {
  open: boolean
  title: string
  description: string
  confirmLabel: string
  onConfirm: () => void
  onCancel: () => void
}

/**
 * Asks before unsaved work is thrown away. Used both ways round: the table mode ending
 * a dirty map edit session, and the map edit mode ending a dirty table session
 * (CONTRACT.md -- "zwei Puffer auf denselben Objekten hält niemand auseinander").
 *
 * Modeled on `InvalidGeometryDialog` / `RectangleSelectConfirmDialog` -- an `AlertDialog`
 * rather than `window.confirm`, to match how every other "are you sure" in this app
 * looks.
 */
export function DiscardEditsDialog({
  open,
  title,
  description,
  confirmLabel,
  onConfirm,
  onCancel,
}: DiscardEditsDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={onCancel}>Abbrechen</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm}>{confirmLabel}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
