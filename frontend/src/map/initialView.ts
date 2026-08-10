import type { ProjectDetail } from '@/api/projects'
import { GERMANY_VIEW } from './basemap'
import type { InitialView } from './MapCanvas'

/**
 * Saved center/zoom wins, then the project's own extent (computed server-side
 * from its layers, contract section 5.6), then Germany as a last resort so the
 * map always has something valid to start with.
 */
export function computeInitialView(project: Pick<ProjectDetail, 'center' | 'zoom' | 'extent'>): InitialView {
  if (project.center && project.zoom != null) {
    return { center: project.center, zoom: project.zoom }
  }
  if (project.extent) {
    const [minLng, minLat, maxLng, maxLat] = project.extent
    return { bounds: [[minLng, minLat], [maxLng, maxLat]] }
  }
  return GERMANY_VIEW
}
