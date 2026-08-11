import { useEffect } from 'react'
import { Check, Eraser, Pentagon, Ruler, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { canFinishSketch, type MeasureMode } from './session'
import { useMeasurement } from './store'

const TOOLS: { mode: MeasureMode; label: string; hint: string; icon: typeof Ruler }[] = [
  {
    mode: 'distance',
    label: 'Strecke messen',
    hint: 'Strecke messen: klicken setzt Punkte, Doppelklick beendet.',
    icon: Ruler,
  },
  {
    mode: 'area',
    label: 'Fläche messen',
    hint: 'Fläche messen: mindestens drei Punkte, Doppelklick schließt die Fläche.',
    icon: Pentagon,
  },
]

interface MeasurementToolbarProps {
  /** Set while editing runs; measuring and drawing would fight over the same click. */
  disabled?: boolean
  /** Shown in place of the tooltip while disabled -- a dead button needs a reason. */
  disabledReason?: string
}

/**
 * The measuring tools, as a control of their own next to the editing toolbar.
 *
 * Deliberately not part of `EditToolbar`: measuring changes nothing and needs no
 * layer, so putting it behind "Bearbeiten" would hide a read-only tool behind a
 * write mode.
 */
export function MeasurementToolbar({
  disabled = false,
  disabledReason = 'Während des Editierens nicht verfügbar',
}: MeasurementToolbarProps) {
  const mode = useMeasurement((state) => state.mode)
  const hasSketch = useMeasurement((state) => state.points.length > 0)
  const canFinish = useMeasurement(canFinishSketch)
  const selectMode = useMeasurement((state) => state.selectMode)
  const clear = useMeasurement((state) => state.clear)
  const finish = useMeasurement((state) => state.finish)
  const exit = useMeasurement((state) => state.exit)

  // A safety net, not the mechanism: `useEditSession.start` already ends the
  // measurement synchronously, before the drawing tool is mounted. This catches any
  // other route into the editing mode, and disabling the buttons alone would leave a
  // live session running behind them.
  useEffect(() => {
    if (disabled) exit()
  }, [disabled, exit])

  // Leaving the workspace ends the session -- the store outlives the route, and a mode
  // that is still on when the map is gone would arm the next map that mounts.
  useEffect(() => exit, [exit])

  return (
    <div className="flex items-center gap-1">
      {TOOLS.map(({ mode: entry, label, hint, icon: Icon }) => {
        const active = mode === entry
        return (
          <Tooltip key={entry}>
            <TooltipTrigger
              render={
                <Button
                  variant={active ? 'secondary' : 'ghost'}
                  size="icon-sm"
                  className={cn('size-7', active && 'ring-1 ring-border')}
                  disabled={disabled}
                  aria-label={label}
                  aria-pressed={active}
                  onClick={() => selectMode(entry)}
                >
                  <Icon className="size-3.5" />
                </Button>
              }
            />
            <TooltipContent>{disabled ? disabledReason : hint}</TooltipContent>
          </Tooltip>
        )
      })}

      {mode !== null && (
        <>
          <Separator orientation="vertical" className="mx-1 h-4 data-vertical:self-center" />

          {/* The only way to close a sketch besides a double-click, and therefore the
              only one a touch screen has -- a double-click cannot be produced there. */}
          <Tooltip>
            <TooltipTrigger
              render={
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="size-7"
                  disabled={!canFinish}
                  aria-label="Messung abschließen"
                  onClick={finish}
                >
                  <Check className="size-3.5" />
                </Button>
              }
            />
            <TooltipContent>
              {canFinish
                ? 'Messung abschließen (auch Doppelklick oder Enter)'
                : 'Es sind noch zu wenige Punkte gesetzt'}
            </TooltipContent>
          </Tooltip>

          <Tooltip>
            <TooltipTrigger
              render={
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="size-7"
                  disabled={!hasSketch}
                  aria-label="Messung zurücksetzen"
                  onClick={clear}
                >
                  <Eraser className="size-3.5" />
                </Button>
              }
            />
            <TooltipContent>
              {hasSketch
                ? 'Messung zurücksetzen (auch Esc) — das Werkzeug bleibt aktiv'
                : 'Es ist nichts gemessen'}
            </TooltipContent>
          </Tooltip>

          <Tooltip>
            <TooltipTrigger
              render={
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="size-7"
                  aria-label="Messmodus beenden"
                  onClick={exit}
                >
                  <X className="size-3.5" />
                </Button>
              }
            />
            <TooltipContent>Messmodus beenden</TooltipContent>
          </Tooltip>
        </>
      )}
    </div>
  )
}
