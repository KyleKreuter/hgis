import type { ProjectDetail } from '@/api/projects'
import { computeInitialView } from './initialView'
import { MapCanvas } from './MapCanvas'
import { MapLayerSync } from './MapLayerSync'
import { ViewportPersistence } from './ViewportPersistence'
import { MapControls } from './controls/MapControls'

interface ProjectMapProps {
  project: ProjectDetail
}

/**
 * The map panel mounted by `routes/projects.$projectId.tsx`. Composes the pieces
 * that each need `useMap()` (only available inside `<MapCanvas>`): layer sync,
 * viewport persistence, and the own-built controls. The map starts clean and
 * without console errors even with zero layers -- track C's tile endpoint lands
 * separately, and `MapLayerSync` already no-ops on an empty list.
 */
export function ProjectMap({ project }: ProjectMapProps) {
  return (
    <MapCanvas initialView={computeInitialView(project)}>
      <MapLayerSync projectId={project.id} />
      <ViewportPersistence projectId={project.id} />
      <MapControls />
    </MapCanvas>
  )
}
