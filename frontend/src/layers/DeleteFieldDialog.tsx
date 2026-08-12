import { useQuery } from '@tanstack/react-query'
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
import { layerFieldUsageQuery, useDeleteLayerField, type LayerField } from '@/api/layers'
import { buildDeleteFieldWarning } from './manageFields'

interface DeleteFieldDialogProps {
  layerId: string
  layerName: string
  projectId: string
  field: LayerField | null
  onOpenChange: (open: boolean) => void
}

/**
 * Confirms deleting one attribute field (CONTRACT.md "Attributfelder löschen"), the
 * counterpart to `DeleteLayerDialog` for a single column instead of the whole layer.
 * Also no typed confirmation -- same reasoning as `DeleteLayerDialog`, one field is a
 * smaller blast radius than a whole layer.
 *
 * Unlike a layer's feature count, the numbers here are not already sitting in a cached
 * summary, so they are fetched fresh (`usage`) the moment a field is chosen for
 * deletion, and the confirm action stays disabled until that answer is in -- asking
 * "really delete?" before knowing what is actually at stake would be a hollow question.
 */
export function DeleteFieldDialog({
  layerId,
  layerName,
  projectId,
  field,
  onOpenChange,
}: DeleteFieldDialogProps) {
  const { data: usage } = useQuery({
    ...layerFieldUsageQuery(layerId, field?.id ?? ''),
    enabled: Boolean(field),
  })
  const deleteField = useDeleteLayerField(layerId, projectId)

  async function handleDelete() {
    if (!field) return
    try {
      await deleteField.mutateAsync(field.id)
      toast.success(`Feld „${field.sourceName}" gelöscht`)
      onOpenChange(false)
    } catch {
      toast.error('Das Programm konnte das Feld nicht löschen')
    }
  }

  return (
    <AlertDialog open={Boolean(field)} onOpenChange={(next) => !next && onOpenChange(false)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Feld löschen?</AlertDialogTitle>
          <AlertDialogDescription>
            {field && (
              <>
                Das Programm löscht „{field.sourceName}" endgültig aus „{layerName}".{' '}
                {usage ? buildDeleteFieldWarning(usage) : 'Prüft…'}
              </>
            )}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Abbrechen</AlertDialogCancel>
          <AlertDialogAction
            onClick={handleDelete}
            disabled={!usage || deleteField.isPending}
            className="bg-destructive text-white hover:bg-destructive/90"
          >
            {deleteField.isPending ? 'Löscht…' : 'Löschen'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
