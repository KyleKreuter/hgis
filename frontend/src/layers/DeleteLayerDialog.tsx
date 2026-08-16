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
 * `DELETE /api/layers/{layerId}` no longer drops the physical table (contract
 * "Schreibstufe" Paket 1 `schutz`) -- it moves the layer into the project's Papierkorb
 * (`trash/TrashDialog.tsx`), where it can be restored or, separately and with its own
 * confirmation, purged for good. The count is still named outright: leaving the map and
 * the attribute table is itself a real change, even though it is no longer the loss it
 * used to be. Unlike deleting a project this does not ask for the name to be typed: one
 * layer is a smaller blast radius than a whole project, and it goes somewhere recoverable
 * besides.
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
      const trashed = await deleteLayer.mutateAsync(layer.id)
      // The count comes only from the response, never from `layer.featureCount` --
      // that value was read when the dialog opened, and `layerKeys.list` refreshes on
      // this browser's own writes only (main.tsx: staleTime 30s, no refetch-on-focus,
      // no polling), so it can be arbitrarily stale by the time this runs, and this
      // dialog holds its own `layer` in a `useState` that would not follow a background
      // update anyway. A server still on `204` sends nothing back; the count-less
      // message is then the honest one, not a guess dressed up as a fact.
      toast.success(
        !isMapImageLayer(layer) && trashed
          ? `Layer „${layer.name}" mit ${formatCount(trashed.featureCount)} ${trashed.featureCount === 1 ? 'Objekt' : 'Objekten'} in den Papierkorb verschoben`
          : `Layer „${layer.name}" in den Papierkorb verschoben`,
      )
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
                Das Programm verschiebt „{layer.name}" in den Papierkorb. Sie können das
                Kartenbild von dort wiederherstellen.
              </>
            )}
            {layer && !deleteLocked && !isMapImageLayer(layer) && (
              <>
                Das Programm verschiebt „{layer.name}" mit{' '}
                <span className="tabular-nums">{formatCount(layer.featureCount)}</span>{' '}
                {layer.featureCount === 1 ? 'Objekt' : 'Objekten'} in den Papierkorb. Sie
                können den Layer von dort wiederherstellen.
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
