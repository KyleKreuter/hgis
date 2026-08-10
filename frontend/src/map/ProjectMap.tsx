import type { ProjectDetail } from '@/api/projects'
import { computeInitialView } from './initialView'
import { MapCanvas } from './MapCanvas'
import { MapLayerSync } from './MapLayerSync'
import { ViewportPersistence } from './ViewportPersistence'
import { ZoomToExtent, type ZoomRequest } from './ZoomToExtent'
import { MapControls } from './controls/MapControls'

interface ProjectMapProps {
  project: ProjectDetail
  /** Set by the layer tree's "zoom to layer"; null while nothing was requested. */
  zoomTo?: ZoomRequest | null
}

/**
 * The map panel mounted by `routes/projects.$projectId.tsx`. Composes the pieces
 * that each need `useMap()` (only available inside `<MapCanvas>`): layer sync,
 * viewport persistence, and the own-built controls. The map starts clean and
 * without console errors even with zero layers -- track C's tile endpoint lands
 * separately, and `MapLayerSync` already no-ops on an empty list.
 */
export function ProjectMap({ project, zoomTo = null }: ProjectMapProps) {
  return (
    <MapCanvas initialView={computeInitialView(project)}>
      <MapLayerSync projectId={project.id} />
      <ViewportPersistence projectId={project.id} />
      <ZoomToExtent request={zoomTo} />
      <MapControls />
    </MapCanvas>
  )
}
