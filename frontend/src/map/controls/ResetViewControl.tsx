import { Maximize } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useMap } from '../MapContext'

/**
 * Back to the whole project: its extent, north up, flat.
 *
 * Distinct from the compass on purpose. The compass undoes the two angles and leaves the
 * user where they are; this one also undoes where they went. Panning far off and losing
 * the data entirely is the easier accident of the two, and zoom buttons alone never lead
 * back from it.
 *
 * `extent` is the project's own, computed server-side from its layers. Without one --
 * an empty project -- there is nothing to return to and the button says so rather than
 * flying somewhere arbitrary.
 */
export function ResetViewControl({ extent }: { extent: [number, number, number, number] | null }) {
  const { mapRef } = useMap()

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      className="rounded-none"
      disabled={!extent}
      aria-label="Gesamtansicht"
      title={extent ? 'Gesamtansicht' : 'Gesamtansicht — das Projekt hat noch keine Daten'}
      onClick={() => {
        const map = mapRef.current
        if (!map || !extent) return
        const [minLng, minLat, maxLng, maxLat] = extent
        map.fitBounds(
          [
            [minLng, minLat],
            [maxLng, maxLat],
          ],
          // padding and maxZoom as in ZoomToExtent: a project whose extent collapses to a
          // single point would otherwise arrive at maximum zoom, showing nothing around it.
          { padding: 48, maxZoom: 17, duration: 600, bearing: 0, pitch: 0 },
        )
      }}
    >
      <Maximize className="size-3.5" />
    </Button>
  )
}
