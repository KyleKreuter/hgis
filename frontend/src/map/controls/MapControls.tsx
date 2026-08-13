import { CompassControl } from './CompassControl'
import { CoordinateReadout } from './CoordinateReadout'
import { ResetViewControl } from './ResetViewControl'
import { ScaleControl } from './ScaleControl'
import { ZoomControl } from './ZoomControl'

/**
 * Positions the own-built controls over the map. A single composition point so
 * the individual controls (`ZoomControl`, `ScaleControl`, `CoordinateReadout`)
 * stay unpositioned and reusable, while overlap between them is handled in one
 * place. The OSM attribution chip is placed directly by `MapCanvas` instead,
 * since it belongs to the basemap rather than to user interaction.
 */
export function MapControls({ extent }: { extent: [number, number, number, number] | null }) {
  return (
    <>
      {/*
        One stack, not two: zoom, compass and reset are all "where am I looking", and a
        single column keeps that one target for the hand. The compass carries its own
        bottom border, so the seams stay even whichever button sits where.
      */}
      <div className="pointer-events-auto absolute top-2 right-2 z-10 flex flex-col overflow-hidden rounded-md border bg-card shadow-sm">
        <ZoomControl />
        <CompassControl />
        <ResetViewControl extent={extent} />
      </div>
      {/*
        z-20, above the attribution line MapCanvas puts in the opposite corner. The two
        share the bottom edge, and at z-10 apiece the attribution won on document order
        alone: on a narrow map it covered the scale bar and its label completely. They no
        longer meet -- the attribution leaves this strip free, see MapCanvas -- so this is
        the guard for the case where a long coordinate readout pushes past that reserve.
      */}
      <div className="pointer-events-none absolute bottom-1 left-1.5 z-20 flex items-end gap-2 [&>*]:pointer-events-auto">
        <CoordinateReadout />
        <ScaleControl />
      </div>
    </>
  )
}
