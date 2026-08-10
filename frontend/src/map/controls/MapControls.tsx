import { CoordinateReadout } from './CoordinateReadout'
import { ScaleControl } from './ScaleControl'
import { ZoomControl } from './ZoomControl'

/**
 * Positions the own-built controls over the map. A single composition point so
 * the individual controls (`ZoomControl`, `ScaleControl`, `CoordinateReadout`)
 * stay unpositioned and reusable, while overlap between them is handled in one
 * place. The OSM attribution chip is placed directly by `MapCanvas` instead,
 * since it belongs to the basemap rather than to user interaction.
 */
export function MapControls() {
  return (
    <>
      <div className="pointer-events-auto absolute top-2 right-2 z-10">
        <ZoomControl />
      </div>
      <div className="pointer-events-none absolute bottom-1 left-1.5 z-10 flex items-end gap-2 [&>*]:pointer-events-auto">
        <CoordinateReadout />
        <ScaleControl />
      </div>
    </>
  )
}
