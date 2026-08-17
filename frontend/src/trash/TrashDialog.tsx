import { useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Loader2, RotateCcw, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { formatCount, formatRelative } from '@/lib/format'
import { trashListQuery, useRestoreLayer, type TrashEntry } from '@/api/trash'
import { PurgeLayerDialog } from './PurgeLayerDialog'

interface TrashDialogProps {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * A project's Papierkorb (contract "Schreibstufe" Paket 3): the one place every layer
 * `DELETE /api/layers/{layerId}` moves into instead of dropping outright ends up visible
 * again. Restoring is one click -- nothing here is lost yet, so there is nothing to ask
 * about. Purging is the one action that is, and it is gated behind `PurgeLayerDialog`
 * (contract "endgueltig loeschen mit Rueckfrage").
 *
 * Nothing here empties the Papierkorb on its own -- the contract calls for a manual one
 * only ("manuell zu leeren"), no expiry and no cleanup job.
 */
export function TrashDialog({ projectId, open, onOpenChange }: TrashDialogProps) {
  const {
    data: entries,
    isPending,
    isError,
    error,
  } = useQuery({
    ...trashListQuery(projectId),
    enabled: open,
  })
  const [purging, setPurging] = useState<TrashEntry | null>(null)

  return (
    <>
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>Papierkorb</DialogTitle>
            <DialogDescription>
              Gelöschte Layer bleiben hier, bis Sie sie wiederherstellen oder endgültig
              löschen.
            </DialogDescription>
          </DialogHeader>

          {isPending ? (
            <p className="py-6 text-center text-xs text-muted-foreground">
              Der Papierkorb wird geladen…
            </p>
          ) : isError ? (
            <div className="py-6 text-center">
              <p className="text-sm font-medium">
                Der Papierkorb konnte nicht geladen werden
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                {error instanceof Error ? error.message : 'Läuft das Backend?'}
              </p>
            </div>
          ) : !entries || entries.length === 0 ? (
            // Contract: "Wenn der Papierkorb leer ist, sagt die Oberflaeche das - nicht
            // eine leere Flaeche."
            <div className="flex flex-col items-center gap-2 py-10 text-center">
              <Trash2 className="size-8 text-muted-foreground" strokeWidth={1.25} />
              <p className="text-sm font-medium">Der Papierkorb ist leer</p>
              <p className="max-w-xs text-xs text-muted-foreground">
                Gelöschte Layer erscheinen hier, solange Sie sie nicht endgültig löschen.
              </p>
            </div>
          ) : (
            <div className="max-h-96 overflow-y-auto rounded-md border">
              <Table>
                <TableHeader className="sticky top-0 z-10 bg-popover">
                  <TableRow>
                    <TableHead className="h-7 text-xs">Name</TableHead>
                    <TableHead className="h-7 text-xs">Gelöscht</TableHead>
                    <TableHead className="h-7 text-xs">Von</TableHead>
                    <TableHead className="h-7 text-right text-xs">Objekte</TableHead>
                    <TableHead className="h-7 w-16 text-xs" />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {entries.map((entry) => (
                    <TrashRow
                      key={entry.id}
                      entry={entry}
                      projectId={projectId}
                      onRequestPurge={() => setPurging(entry)}
                    />
                  ))}
                </TableBody>
              </Table>
            </div>
          )}

          <DialogFooter>
            <Button type="button" onClick={() => onOpenChange(false)}>
              Schließen
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <PurgeLayerDialog
        entry={purging}
        projectId={projectId}
        onOpenChange={(next) => !next && setPurging(null)}
      />
    </>
  )
}

/** One trashed layer, with its own restore mutation so a save in progress only disables this row. */
function TrashRow({
  entry,
  projectId,
  onRequestPurge,
}: {
  entry: TrashEntry
  projectId: string
  onRequestPurge: () => void
}) {
  const restoreLayer = useRestoreLayer(projectId)
  // `disabled={restoreLayer.isPending}` below only takes effect once React has
  // re-rendered -- two clicks fired before that render (e.g. a fast double click) both
  // go through and fire two POSTs. This ref is checked and set synchronously inside the
  // handler itself, so the second click is turned away regardless of render timing.
  const restoring = useRef(false)

  async function handleRestore() {
    if (restoring.current) return
    restoring.current = true
    try {
      await restoreLayer.mutateAsync(entry.id)
      toast.success(`Layer „${entry.name}" wiederhergestellt`)
    } catch {
      toast.error('Das Programm konnte den Layer nicht wiederherstellen')
    } finally {
      restoring.current = false
    }
  }

  return (
    <TableRow>
      <TableCell className="p-1.5 align-top">
        <span className="block max-w-48 truncate" title={entry.name}>
          {entry.name}
        </span>
      </TableCell>
      <TableCell className="p-1.5 align-top text-xs text-muted-foreground">
        {formatRelative(entry.deletedAt)}
      </TableCell>
      <TableCell className="p-1.5 align-top text-xs text-muted-foreground">
        {entry.deletedBy ?? '–'}
      </TableCell>
      <TableCell className="p-1.5 align-top text-right text-xs text-muted-foreground tabular-nums">
        {formatCount(entry.featureCount)}
      </TableCell>
      <TableCell className="p-1.5 align-top">
        <div className="flex items-center justify-end gap-0.5">
          <Tooltip>
            <TooltipTrigger
              render={
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={handleRestore}
                  disabled={restoreLayer.isPending}
                  aria-label={`Layer „${entry.name}" wiederherstellen`}
                >
                  {restoreLayer.isPending ? (
                    <Loader2 className="size-3.5 animate-spin" />
                  ) : (
                    <RotateCcw className="size-3.5" />
                  )}
                </Button>
              }
            />
            <TooltipContent>Wiederherstellen</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger
              render={
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                  onClick={onRequestPurge}
                  aria-label={`Layer „${entry.name}" endgültig löschen`}
                >
                  <Trash2 className="size-3.5" />
                </Button>
              }
            />
            <TooltipContent>Endgültig löschen</TooltipContent>
          </Tooltip>
        </div>
      </TableCell>
    </TableRow>
  )
}
