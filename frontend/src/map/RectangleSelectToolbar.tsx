import { useEffect } from 'react'
import { BoxSelect, Info } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import type { SpatialMode } from '@/api/features'
import { useRectangleSelect } from './rectangleSelectStore'

const TOUCH_MODE_LABELS: Record<SpatialMode, string> = {
  intersects: 'berührt',
  contains: 'vollständig enthalten',
}

interface RectangleSelectToolbarProps {
  /** Set while editing runs; the rectangle tool would fight the drawing tool for the same drag. */
  disabled?: boolean
  /** No active layer chosen yet -- there is nothing to select from. */
  canUse: boolean
  /**
   * The active layer's `clipVersion` (contract phase 19), or `0` when no mask applies
   * to it. A rectangle selection queries the database, not the map picture, so it also
   * finds objects the clip mask hides -- worth a standing note while the tool is armed
   * on such a layer, not a toast repeated on every drag.
   */
  clipVersion?: number
}

/**
 * The rectangle select tool, as a control of its own next to measuring and editing.
 *
 * One toggle button arms the tool; while armed, a segmented control picks whether a
 * touched object suffices ('intersects') or has to lie fully inside the rectangle
 * ('contains') -- shown only then, the same way the measuring toolbar only shows its
 * finish/clear/exit buttons once a sketch is started.
 */
export function RectangleSelectToolbar({ disabled = false, canUse, clipVersion = 0 }: RectangleSelectToolbarProps) {
  const active = useRectangleSelect((state) => state.active)
  const touchMode = useRectangleSelect((state) => state.touchMode)
  const loading = useRectangleSelect((state) => state.loading)
  const activate = useRectangleSelect((state) => state.activate)
  const deactivate = useRectangleSelect((state) => state.deactivate)
  const setTouchMode = useRectangleSelect((state) => state.setTouchMode)

  // A safety net, not the mechanism: `useEditSession.start` cannot know about this
  // tool, so it has no way to end it before the drawing tool mounts. This catches
  // editing starting while the rectangle tool is armed -- mirrors `MeasurementToolbar`.
  useEffect(() => {
    if (disabled) deactivate()
  }, [disabled, deactivate])

  // Leaving the workspace ends the tool -- the store outlives the route, and a tool
  // still armed when the map is gone would be armed again for the next map that mounts.
  useEffect(() => deactivate, [deactivate])

  const blocked = disabled || !canUse

  return (
    <div className="flex items-center gap-1.5">
      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant={active ? 'secondary' : 'ghost'}
              size="icon-sm"
              className={cn('size-7', active && 'ring-1 ring-border')}
              disabled={blocked}
              aria-label="Rechteckauswahl"
              aria-pressed={active}
              onClick={() => (active ? deactivate() : activate())}
            >
              <BoxSelect className="size-3.5" />
            </Button>
          }
        />
        <TooltipContent className="max-w-xs">
          {canUse
            ? 'Objekte per Rechteck auswählen: ohne Taste ersetzen, mit Shift ergänzen, mit Alt abziehen'
            : 'Wählen Sie zuerst einen Layer im Layerbaum aus'}
        </TooltipContent>
      </Tooltip>

      {active && (
        <>
          <Select value={touchMode} onValueChange={(value) => setTouchMode(value as SpatialMode)}>
            <SelectTrigger size="sm" className="h-7 w-auto" aria-label="Auswahlmodus des Rechtecks">
              <SelectValue>{(value: string) => TOUCH_MODE_LABELS[value as SpatialMode]}</SelectValue>
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="intersects">{TOUCH_MODE_LABELS.intersects}</SelectItem>
              <SelectItem value="contains">{TOUCH_MODE_LABELS.contains}</SelectItem>
            </SelectContent>
          </Select>

          {/* Only feedback that a rectangle is being resolved; the tool itself has no
              progress bar to show -- the server streams pages, the browser shows none. */}
          {loading && <span className="text-xs text-muted-foreground">Lädt…</span>}

          {/* A standing note, not a message per drag: the tool stays armed for many
              selections in a row, and repeating this on every one would train users to
              dismiss it without reading. Shown only while it actually applies -- the
              active layer carries a mask's cut right now. */}
          {clipVersion > 0 && (
            <Tooltip>
              <TooltipTrigger
                render={
                  <span
                    tabIndex={0}
                    className="text-muted-foreground"
                    aria-label="Die Auswahl findet auch Objekte, die der Zuschnitt aus der Karte schneidet"
                  >
                    <Info className="size-3.5" />
                  </span>
                }
              />
              <TooltipContent className="max-w-xs">
                Der Zuschnitt betrifft nur die Darstellung. Die Auswahl findet auch Objekte außerhalb der Maske.
              </TooltipContent>
            </Tooltip>
          )}
        </>
      )}
    </div>
  )
}
