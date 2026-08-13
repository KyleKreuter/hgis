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
import { isMapImageLayer, useDeleteLayer, type LayerSummary } from '@/api/layers'
import { useEditing } from '@/state/editing'
import { useTableEditing } from '@/table/useTableEditing'

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

  // Locked while this exact layer has an open edit session, map or table -- dropping its
  // table out from under a running buffer would leave that buffer pointed at a layer that
  // no longer exists. Same reasoning, and the same `deleteLocked` shape, as the field-
  // delete lock in `ManageFieldsDialog`; a different layer being edited elsewhere is
  // unaffected.
  const mapEditingLayerId = useEditing((state) => state.layerId)
  const tableEditingLayerId = useTableEditing((state) => state.layerId)
  const deleteLocked =
    Boolean(layer) && (mapEditingLayerId === layer?.id || tableEditingLayerId === layer?.id)

  async function handleDelete() {
    if (!layer || deleteLocked) return
    try {
      await deleteLayer.mutateAsync(layer.id)
      toast.success(`Layer „${layer.name}" gelöscht`)
      onDeleted(layer.id)
      onOpenChange(false)
    } catch {
      toast.error('Das Programm konnte den Layer nicht löschen')
    }
  }

  return (
    <AlertDialog open={Boolean(layer)} onOpenChange={(next) => !next && onOpenChange(false)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Layer löschen?</AlertDialogTitle>
          <AlertDialogDescription>
            {layer && deleteLocked && (
              <>
                Sie bearbeiten „{layer.name}" gerade. Deshalb können Sie den Layer nicht
                löschen. Speichern oder verwerfen Sie zuerst die laufende Bearbeitung.
              </>
            )}
            {/* A Kartenbild has no objects to count -- "mit 0 Objekten" would read as an
                empty layer rather than as a picture, which has no such count at all. */}
            {layer && !deleteLocked && isMapImageLayer(layer) && (
              <>
                Das Programm löscht „{layer.name}" endgültig. Sie können das Kartenbild
                danach nicht wiederherstellen.
              </>
            )}
            {layer && !deleteLocked && !isMapImageLayer(layer) && (
              <>
                Das Programm löscht „{layer.name}" mit{' '}
                <span className="tabular-nums">{formatCount(layer.featureCount)}</span>{' '}
                {layer.featureCount === 1 ? 'Objekt' : 'Objekten'} endgültig. Sie können die
                Daten danach nicht wiederherstellen.
              </>
            )}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Abbrechen</AlertDialogCancel>
          <AlertDialogAction
            onClick={handleDelete}
            disabled={deleteLayer.isPending || deleteLocked}
            className="bg-destructive text-white hover:bg-destructive/90"
          >
            {deleteLayer.isPending ? 'Wird gelöscht…' : 'Löschen'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
