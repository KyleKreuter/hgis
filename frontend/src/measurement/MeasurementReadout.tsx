import { useMemo } from 'react'
import { Pentagon, Ruler } from 'lucide-react'
import { formatArea, formatDistance } from './format'
import { measurementResult, type MeasureMode } from './session'
import { useMeasurement } from './store'

const MODE_LABEL: Record<MeasureMode, string> = {
  distance: 'Strecke',
  area: 'Fläche',
}

const HINT: Record<MeasureMode, string> = {
  distance: 'Klicken setzt Punkte · Doppelklick beendet',
  area: 'Mindestens drei Punkte · Doppelklick beendet',
}

/**
 * The running result, over the top-left corner of the map.
 *
 * Sits opposite the scale bar and the coordinate readout rather than next to them:
 * those describe the view and are always there, this one describes a measurement and
 * only exists while one is being taken.
 */
export function MeasurementReadout() {
  const mode = useMeasurement((state) => state.mode)
  const points = useMeasurement((state) => state.points)
  const cursor = useMeasurement((state) => state.cursor)
  const finished = useMeasurement((state) => state.finished)

  const result = useMemo(
    () => measurementResult({ mode, points, cursor, finished }),
    [mode, points, cursor, finished],
  )

  if (!result) return null

  const Icon = result.mode === 'area' ? Pentagon : Ruler
  const primary =
    result.mode === 'area' ? formatArea(result.area ?? 0) : formatDistance(result.length)
  const secondary =
    result.mode === 'area'
      ? `Umfang ${formatDistance(result.length)}`
      : `${result.vertexCount} ${result.vertexCount === 1 ? 'Punkt' : 'Punkte'}`

  return (
    <div
      // aria-live is off on purpose: the value changes with every mouse move, and a
      // screen reader reading each of them out is unusable. The finished result is
      // announced once, below.
      role="status"
      aria-live="off"
      // The width is capped against the map panel, not the window: the basemap picker
      // and the zoom stack occupy the opposite corner, and on a narrow panel the
      // readout would otherwise grow underneath them.
      className="absolute top-2 left-2 z-10 min-w-40 max-w-[calc(100cqw-6rem)] rounded-md border bg-background/90 px-2.5 py-2 shadow-sm backdrop-blur-sm @max-xs:min-w-0"
    >
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
        <Icon className="size-3.5" />
        <span>{MODE_LABEL[result.mode]}</span>
        {result.finished && <span className="ml-auto">abgeschlossen</span>}
      </div>

      <div className="mt-0.5 text-base font-medium tabular-nums">
        {result.meaningful ? primary : '—'}
      </div>

      <div className="text-xs text-muted-foreground tabular-nums">
        {result.meaningful ? secondary : HINT[result.mode]}
      </div>

      <span className="sr-only" aria-live="polite">
        {result.finished && result.meaningful
          ? `${MODE_LABEL[result.mode]} gemessen: ${primary}, ${secondary}`
          : ''}
      </span>
    </div>
  )
}
