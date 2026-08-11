import { MeasurementLayer } from './MeasurementLayer'
import { MeasurementReadout } from './MeasurementReadout'

/**
 * Everything the measuring tool puts inside the map: the sketch itself and the panel
 * that reads it out. Mounted as a child of `<ProjectMap>`, which is where `useMap()`
 * is available and where an absolutely positioned panel lands over the canvas.
 */
export function MeasurementOverlay() {
  return (
    <>
      <MeasurementLayer />
      <MeasurementReadout />
    </>
  )
}
