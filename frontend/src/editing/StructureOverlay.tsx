import { X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { formatCount } from '@/lib/format'
import type { LayerField } from '@/api/layers'
import { useSelection } from '@/state/selection'
import { MergeDialog } from './MergeDialog'
import { SplitConfirmDialog } from './SplitConfirmDialog'
import { SplitLineTool } from './SplitLineTool'
import { SPLIT_LINE_MIN_POINTS } from './splitLine'
import { useStructure } from './structureStore'

interface StructureOverlayProps {
  layerId: string
  projectId: string
  /** The active layer's fields; the merge dialog shows them so the lead can be picked. */
  fields: LayerField[]
}

/**
 * Everything the two structural tools put inside the map: the line being drawn, its
 * instructions, and the two confirmations.
 *
 * Mounted as a child of `<ProjectMap>`, which is where `useMap()` is available and where
 * an absolutely positioned panel lands over the canvas -- the same arrangement
 * `MeasurementOverlay` uses. The dialogs are portalled out of here anyway, so their
 * position in the tree costs nothing; keeping them next to the tool they belong to is
 * what stops the route from having to know about a phase machine.
 *
 * Both actions end by putting their result into the selection: after a split the parts,
 * after a merge the surviving object. Whatever the user was working on is still what is
 * selected afterwards, so the next action does not start with a hunt for it.
 */
export function StructureOverlay({ layerId, projectId, fields }: StructureOverlayProps) {
  const phase = useStructure((state) => state.phase)
  const cancel = useStructure((state) => state.cancel)
  const redrawLine = useStructure((state) => state.redrawLine)
  const selectionLayerId = useSelection((state) => state.layerId)
  const selected = useSelection((state) => state.selected)
  const select = useSelection((state) => state.select)

  if (phase.type === 'drawing') {
    return (
      <>
        <SplitLineTool />
        <SplitLineHint pointCount={phase.points.length} fid={phase.fid} onCancel={cancel} />
      </>
    )
  }

  if (phase.type === 'confirmSplit') {
    return (
      <SplitConfirmDialog
        layerId={layerId}
        projectId={projectId}
        fid={phase.fid}
        rowVersion={phase.rowVersion}
        line={phase.line}
        onRedraw={redrawLine}
        onCancel={cancel}
        onDone={(fids) => {
          select(layerId, fids)
          cancel()
          toast.success(`Das Programm hat das Objekt in ${formatCount(fids.length)} Teile geteilt`)
        }}
      />
    )
  }

  if (phase.type === 'merge') {
    // Read at render time rather than captured when the dialog opened: the selection is
    // what the merge is about, and it cannot change behind the dialog's back anyway --
    // the map's click handling sits behind it while it is open.
    const fids = selectionLayerId === layerId ? [...selected] : []
    return (
      <MergeDialog
        layerId={layerId}
        projectId={projectId}
        fids={fids}
        fields={fields}
        onCancel={cancel}
        onDone={(fid) => {
          select(layerId, [fid])
          cancel()
          toast.success(
            `Das Programm hat ${formatCount(fids.length)} Objekte zu Objekt ${fid} zusammengeführt`,
          )
        }}
      />
    )
  }

  return null
}

interface SplitLineHintProps {
  fid: number
  pointCount: number
  onCancel: () => void
}

/**
 * What to do while the line is being drawn, and a way out that does not require knowing
 * that Escape is one.
 *
 * Sits at the bottom of the map: the top left is where the measurement readout appears
 * and the top right holds the basemap picker and the zoom stack, and a panel that covers
 * the object being cut would defeat its own purpose.
 */
function SplitLineHint({ fid, pointCount, onCancel }: SplitLineHintProps) {
  const enough = pointCount >= SPLIT_LINE_MIN_POINTS

  return (
    <div className="absolute bottom-4 left-1/2 z-10 -translate-x-1/2 rounded-md bg-popover/95 px-3 py-2 text-xs shadow-md ring-1 ring-foreground/10">
      <div className="flex items-center gap-3">
        <div>
          <div className="font-medium">Objekt {fid} teilen</div>
          <div className="mt-0.5 text-muted-foreground">
            {enough
              ? 'Doppelklick oder Enter beendet die Linie. Rechtsklick nimmt einen Punkt zurück.'
              : 'Klicken Sie zwei oder mehr Punkte über das Objekt.'}
          </div>
        </div>
        <Button variant="ghost" size="icon-sm" className="size-6" aria-label="Teilen abbrechen" onClick={onCancel}>
          <X className="size-3.5" />
        </Button>
      </div>
    </div>
  )
}
