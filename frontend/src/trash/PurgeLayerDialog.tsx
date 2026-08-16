import { toast } from 'sonner'
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
import { usePurgeLayer, type TrashEntry } from '@/api/trash'

interface PurgeLayerDialogProps {
  entry: TrashEntry | null
  projectId: string
  onOpenChange: (open: boolean) => void
}

/**
 * Confirms dropping one trashed layer for good (contract "endgueltig loeschen mit
 * Rueckfrage") -- the counterpart to `DeleteLayerDialog` (`layers/DeleteLayerDialog.tsx`)
 * for a layer that already sits in the Papierkorb. No lock check like that dialog's: a
 * layer here is invisible to the map and the attribute table, so it cannot be the target
 * of an open edit session in the first place.
 */
export function PurgeLayerDialog({ entry, projectId, onOpenChange }: PurgeLayerDialogProps) {
  const purgeLayer = usePurgeLayer(projectId)

  async function handlePurge() {
    if (!entry) return
    try {
      await purgeLayer.mutateAsync(entry.id)
      toast.success(`Layer „${entry.name}" endgültig gelöscht`)
      onOpenChange(false)
    } catch {
      toast.error('Das Programm konnte den Layer nicht endgültig löschen')
    }
  }

  return (
    <AlertDialog open={Boolean(entry)} onOpenChange={(next) => !next && onOpenChange(false)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Layer endgültig löschen?</AlertDialogTitle>
          <AlertDialogDescription>
            {entry && (
              <>
                Das Programm löscht „{entry.name}" mit{' '}
                <span className="tabular-nums">{formatCount(entry.featureCount)}</span>{' '}
                {entry.featureCount === 1 ? 'Objekt' : 'Objekten'} endgültig aus dem
                Papierkorb. Das lässt sich nicht rückgängig machen.
              </>
            )}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={purgeLayer.isPending}>Abbrechen</AlertDialogCancel>
          <AlertDialogAction
            onClick={handlePurge}
            disabled={purgeLayer.isPending}
            className="bg-destructive text-white hover:bg-destructive/90"
          >
            {purgeLayer.isPending ? 'Wird gelöscht…' : 'Endgültig löschen'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
