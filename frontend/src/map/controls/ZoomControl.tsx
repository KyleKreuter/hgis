import { Minus, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useMap } from '../MapContext'

/**
 * Replaces MapLibre's default NavigationControl (disabled via `attributionControl:
 * false` and simply never adding a NavigationControl) with a shadcn-styled pair of
 * buttons, per the compact tool scale used everywhere else in the app.
 */
export function ZoomControl() {
  const { mapRef } = useMap()

  return (
    <div className="flex flex-col overflow-hidden rounded-md border bg-card shadow-sm">
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        className="rounded-none border-b"
        aria-label="Vergrößern"
        onClick={() => mapRef.current?.zoomIn()}
      >
        <Plus className="size-3.5" />
      </Button>
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        className="rounded-none"
        aria-label="Verkleinern"
        onClick={() => mapRef.current?.zoomOut()}
      >
        <Minus className="size-3.5" />
      </Button>
    </div>
  )
}
