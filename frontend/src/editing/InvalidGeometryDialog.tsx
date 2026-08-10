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

interface InvalidGeometryDialogProps {
  /** The server's reason, including the coordinate it found the problem at. */
  message: string | null
  onRepair: () => void
  onCancel: () => void
}

/**
 * Asked when the server refused a geometry as invalid.
 *
 * The repair is a decision, not a fallback: `ST_MakeValid` changes the shape, and a
 * self-intersecting polygon can come back as something with a different outline than the
 * one that was drawn. So the message states what is wrong and where, and offers both
 * ways out -- repair, or go back and fix it by hand.
 */
export function InvalidGeometryDialog({ message, onRepair, onCancel }: InvalidGeometryDialogProps) {
  return (
    <AlertDialog open={Boolean(message)} onOpenChange={(next) => !next && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Geometrie ist ungültig</AlertDialogTitle>
          <AlertDialogDescription>
            {message}
            <span className="mt-2 block">
              Beim Reparieren wird die Form verändert — sie kann danach anders aussehen als
              gezeichnet. Es wurde noch nichts gespeichert.
            </span>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Selbst korrigieren</AlertDialogCancel>
          <AlertDialogAction onClick={onRepair}>Automatisch reparieren</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
