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
import { useDeleteLayer, type LayerSummary } from '@/api/layers'

interface DeleteLayerDialogProps {
  layer: LayerSummary | null
  projectId: string
  onOpenChange: (open: boolean) => void
  onDeleted: (layerId: string) => void
}

/**
 * Deleting a layer drops its physical table, so the count is named outright -- the
 * number is the only thing that conveys what is at stake. Unlike deleting a project
 * this does not ask for the name to be typed: it destroys one layer, not everything,
 * and a hurdle placed everywhere only teaches people to push past it.
 */
export function DeleteLayerDialog({
  layer,
  projectId,
  onOpenChange,
  onDeleted,
}: DeleteLayerDialogProps) {
  const deleteLayer = useDeleteLayer(projectId)

  async function handleDelete() {
    if (!layer) return
    try {
      await deleteLayer.mutateAsync(layer.id)
      toast.success(`Layer „${layer.name}" gelöscht`)
      onDeleted(layer.id)
      onOpenChange(false)
    } catch {
      toast.error('Layer konnte nicht gelöscht werden')
    }
  }

  return (
    <AlertDialog open={Boolean(layer)} onOpenChange={(next) => !next && onOpenChange(false)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Layer löschen?</AlertDialogTitle>
          <AlertDialogDescription>
            {layer && (
              <>
                „{layer.name}" wird mit{' '}
                <span className="tabular-nums">{formatCount(layer.featureCount)}</span>{' '}
                {layer.featureCount === 1 ? 'Objekt' : 'Objekten'} endgültig entfernt.
                Die Daten lassen sich danach nicht wiederherstellen.
              </>
            )}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Abbrechen</AlertDialogCancel>
          <AlertDialogAction
            onClick={handleDelete}
            disabled={deleteLayer.isPending}
            className="bg-destructive text-white hover:bg-destructive/90"
          >
            {deleteLayer.isPending ? 'Wird gelöscht…' : 'Löschen'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
