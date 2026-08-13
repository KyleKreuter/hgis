import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Combine, Scissors } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { ApiError } from '@/api/client'
import { featureDetailQuery } from '@/api/features'
import type { GeometryType } from '@/api/layers'
import { useSelection } from '@/state/selection'
import {
  mergeBlockReason,
  splitBlockReason,
  splitObjection,
  structureLockReason,
  structureToolsFor,
} from './structureTools'
import { useStructure } from './structureStore'

interface StructureToolbarProps {
  /** The active layer, or null while none is open. */
  layerId: string | null
  geometryType?: GeometryType
  /**
   * Unsaved changes across both edit modes -- the same count the leave guard blocks on.
   * Anything above zero locks both tools; see `structureLockReason`.
   */
  pendingChanges: number
  /** Whether the map's drawing mode is running. */
  drawingActive: boolean
}

/**
 * The two structural tools, as a control of its own next to measuring, the rectangle
 * select and the drawing tools.
 *
 * Both work on the map's ordinary selection -- what Identify, the attribute table and
 * the rectangle tool all write to -- rather than on a selection of their own. There is
 * one fact about which objects are selected in this application, and a second one owned
 * by these two buttons would have to be kept in step with it for no gain.
 *
 * Hidden entirely on a point layer, unlike the drawing tools, which grey out instead:
 * a disabled scissors on a point layer would suggest cutting a point is a thing that
 * exists and is merely unavailable right now.
 */
export function StructureToolbar({
  layerId,
  geometryType,
  pendingChanges,
  drawingActive,
}: StructureToolbarProps) {
  const queryClient = useQueryClient()
  const selectionLayerId = useSelection((state) => state.layerId)
  const selected = useSelection((state) => state.selected)
  const phase = useStructure((state) => state.phase)
  const startSplit = useStructure((state) => state.startSplit)
  const openMerge = useStructure((state) => state.openMerge)
  const cancel = useStructure((state) => state.cancel)

  // Leaving the workspace ends the tool -- the store outlives the route, and a tool still
  // armed when the map is gone would be armed again for the next map that mounts. Same
  // reasoning as `RectangleSelectToolbar`'s own unmount cleanup.
  useEffect(() => cancel, [cancel])

  // A layer switch, a drawing session starting, or an edit that just made the buffer
  // dirty all pull the ground out from under a running split: the captured fid belongs to
  // the layer it was armed on, and the tools may not be used at all while anything is
  // unsaved. Ended rather than left standing, because the line being drawn would
  // otherwise still be sent afterwards.
  const locked = structureLockReason(pendingChanges, drawingActive) !== null
  useEffect(() => {
    cancel()
  }, [layerId, locked, cancel])

  // A fid only identifies a row within its own layer, so a selection left over from
  // another one says nothing about this one.
  const selectedFids = selectionLayerId === layerId ? [...selected] : []

  const lock = structureLockReason(pendingChanges, drawingActive)
  const splitReason = splitBlockReason(selectedFids.length, lock)
  const mergeReason = mergeBlockReason(selectedFids.length, lock)
  const drawing = phase.type === 'drawing'

  /**
   * Loads the one feature the split is about, then arms the drawing.
   *
   * Two things are needed before a line can be drawn, and both come from the row itself:
   * its `rowVersion`, which says which state of it the cut was planned against, and its
   * geometry -- on a `GEOMETRY` layer the column says nothing about the individual row,
   * and a point has to be refused before the drawing starts rather than after.
   */
  async function arm(fid: number | undefined) {
    if (!layerId || fid === undefined) return
    try {
      const feature = await queryClient.fetchQuery(featureDetailQuery(layerId, fid))
      const objection = splitObjection(feature.geometry?.type)
      if (objection) {
        toast.error(objection)
        return
      }
      startSplit(fid, feature.rowVersion)
    } catch (error) {
      toast.error(
        error instanceof ApiError ? error.message : 'Das Programm konnte das Objekt nicht laden',
      )
    }
  }

  if (!layerId || !geometryType) return null
  const tools = structureToolsFor(geometryType)
  if (tools.length === 0) return null

  return (
    <div className="flex items-center gap-1">
      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant={drawing ? 'secondary' : 'ghost'}
              size="icon-sm"
              className={cn('size-7', drawing && 'ring-1 ring-border')}
              disabled={!drawing && splitReason !== null}
              aria-label="Objekt teilen"
              aria-pressed={drawing}
              onClick={() => (drawing ? cancel() : void arm(selectedFids[0]))}
            >
              <Scissors className="size-3.5" />
            </Button>
          }
        />
        <TooltipContent className="max-w-xs">
          {/* The reason is in the tooltip rather than nowhere: a button that is dead
              without saying why looks like a broken feature. */}
          {drawing
            ? 'Linie über das Objekt zeichnen. Doppelklick beendet sie, Esc bricht ab.'
            : (splitReason ??
              'Ausgewähltes Objekt an einer gezeichneten Linie teilen. Das lässt sich nicht rückgängig machen.')}
        </TooltipContent>
      </Tooltip>

      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant="ghost"
              size="icon-sm"
              className="size-7"
              disabled={mergeReason !== null}
              aria-label="Objekte zusammenführen"
              onClick={openMerge}
            >
              <Combine className="size-3.5" />
            </Button>
          }
        />
        <TooltipContent className="max-w-xs">
          {mergeReason ??
            'Ausgewählte Objekte zu einem zusammenführen. Das lässt sich nicht rückgängig machen.'}
        </TooltipContent>
      </Tooltip>

      {/* Carried here rather than by the workspace: this whole control disappears on a
          point layer, and a separator the route placed would stay behind next to the
          one before it. */}
      <Separator orientation="vertical" className="mx-1 h-4 data-vertical:self-center" />
    </div>
  )
}
