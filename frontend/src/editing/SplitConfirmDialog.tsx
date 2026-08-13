import { TriangleAlert } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogMedia,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { formatCount } from '@/lib/format'
import { useSplitFeature } from '@/api/structure'
import { structureErrorMessage } from './structureTools'

interface SplitConfirmDialogProps {
  layerId: string
  projectId: string
  /** The feature to cut. It survives the split and takes the first part. */
  fid: number
  /** xmin captured when the tool was armed -- see `structureStore`. */
  rowVersion: string
  line: GeoJSON.LineString
  /** Back to drawing, with the line dropped. */
  onRedraw: () => void
  onCancel: () => void
  /** The parts, original first. */
  onDone: (fids: number[]) => void
}

/**
 * The last step before a split is written.
 *
 * There is a confirmation at all because this is one of the two actions the editor's undo
 * cannot reach: it writes straight through, so by the time anything looks wrong the old
 * geometry is gone. Saying so beforehand is the whole point of the step -- the count of
 * parts is not knowable yet (PostGIS decides that), so what the dialog can state instead
 * is what is at stake and what the parts will inherit.
 *
 * A failed request leaves the dialog standing with the reason in it rather than closing
 * and dropping a toast: the line is still drawn, and "Neu zeichnen" is a real answer to
 * "Die Linie teilt das Objekt nicht."
 */
export function SplitConfirmDialog({
  layerId,
  projectId,
  fid,
  rowVersion,
  line,
  onRedraw,
  onCancel,
  onDone,
}: SplitConfirmDialogProps) {
  const split = useSplitFeature(layerId, projectId)

  async function confirm() {
    try {
      const result = await split.mutateAsync({ fid, line, rowVersion })
      onDone(result.fids)
    } catch {
      // Shown below, from `split.error`. Swallowed here only so an unhandled rejection
      // does not take the workspace down with it.
    }
  }

  return (
    <AlertDialog open onOpenChange={(next) => !next && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogMedia>
            <TriangleAlert className="text-destructive" />
          </AlertDialogMedia>
          <AlertDialogTitle>Objekt {fid} teilen?</AlertDialogTitle>
          <AlertDialogDescription>
            Die Linie hat {formatCount(line.coordinates.length)} Stützpunkte. Das Programm teilt das
            Objekt sofort auf dem Server.
            <span className="mt-2 block">
              Sie können das nicht rückgängig machen. Alle Teile behalten die Attribute des Objekts.
            </span>
          </AlertDialogDescription>
        </AlertDialogHeader>

        {split.error && (
          <p role="alert" className="text-sm text-destructive">
            {structureErrorMessage(split.error, 'split')}
          </p>
        )}

        <AlertDialogFooter>
          <Button
            variant="ghost"
            disabled={split.isPending}
            onClick={() => {
              split.reset()
              onRedraw()
            }}
          >
            Neu zeichnen
          </Button>
          <AlertDialogCancel disabled={split.isPending} onClick={onCancel}>
            Abbrechen
          </AlertDialogCancel>
          <AlertDialogAction disabled={split.isPending} onClick={() => void confirm()}>
            {split.isPending ? 'Wird geteilt…' : 'Teilen'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
