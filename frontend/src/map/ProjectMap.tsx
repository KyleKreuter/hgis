import type { ReactNode } from 'react'
import type { ProjectDetail } from '@/api/projects'
import { computeInitialView } from './initialView'
import { MapCanvas } from './MapCanvas'
import { MapLayerSync } from './MapLayerSync'
import { ViewportPersistence } from './ViewportPersistence'
import { ZoomToExtent, type ZoomRequest } from './ZoomToExtent'
import { IdentifyControl } from './IdentifyControl'
import { SelectionHighlight } from './SelectionHighlight'
import { MapControls } from './controls/MapControls'

interface ProjectMapProps {
  project: ProjectDetail
  /** Set by the layer tree's "zoom to layer"; null while nothing was requested. */
  zoomTo?: ZoomRequest | null
  /** Restricts Identify to one layer, so clicking through a stack stays predictable. */
  activeLayerId?: string | null
  /** Mounted inside the canvas; used for the editing pieces, which all need `useMap()`. */
  children?: ReactNode
  /** Identify would fight the drawing tool for the click, so it stands down while editing. */
  identifyEnabled?: boolean
}

/**
 * The map panel mounted by `routes/projects.$projectId.tsx`. Composes the pieces
 * that each need `useMap()` (only available inside `<MapCanvas>`): layer sync,
 * viewport persistence, and the own-built controls. The map starts clean and
 * without console errors even with zero layers -- track C's tile endpoint lands
 * separately, and `MapLayerSync` already no-ops on an empty list.
 */
export function ProjectMap({
  project,
  zoomTo = null,
  activeLayerId = null,
  identifyEnabled = true,
  children,
}: ProjectMapProps) {
  return (
    <MapCanvas initialView={computeInitialView(project)}>
      <MapLayerSync projectId={project.id} />
      <ViewportPersistence projectId={project.id} />
      <ZoomToExtent request={zoomTo} />
      <SelectionHighlight projectId={project.id} />
      {identifyEnabled && <IdentifyControl activeLayerId={activeLayerId} />}
      {children}
      <MapControls />
    </MapCanvas>
  )
}
