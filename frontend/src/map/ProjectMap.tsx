import type { ReactNode } from 'react'
import type { LayerSummary } from '@/api/layers'
import type { ProjectDetail } from '@/api/projects'
import { BasemapControl } from './BasemapControl'
import { computeInitialView } from './initialView'
import { MapCanvas } from './MapCanvas'
import { MapLayerSync } from './MapLayerSync'
import { ViewportPersistence } from './ViewportPersistence'
import { ZoomToExtent, type ZoomRequest } from './ZoomToExtent'
import { IdentifyControl } from './IdentifyControl'
import { resolveBasemapSettings } from './resolveBasemapSettings'
import { SelectionHighlight } from './SelectionHighlight'
import { MapControls } from './controls/MapControls'

interface ProjectMapProps {
  project: ProjectDetail
  /** Set by the layer tree's "zoom to layer"; null while nothing was requested. */
  zoomTo?: ZoomRequest | null
  /**
   * The active layer, or null while none is selected. Drives both Identify (restricted
   * to this one layer, so clicking through a stack stays predictable) and the basemap
   * picker's "just this layer" scope (CONTRACT.md phase 18).
   */
  activeLayer?: LayerSummary | null
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
  activeLayer = null,
  identifyEnabled = true,
  children,
}: ProjectMapProps) {
  // The active layer's own basemap/opacity win over the project's, decided separately
  // for each (CONTRACT.md). `MapCanvas` only ever sees the already-resolved result --
  // it knows nothing about layers or projects.
  const resolved = resolveBasemapSettings(activeLayer, project)

  return (
    <MapCanvas
      initialView={computeInitialView(project)}
      basemapId={resolved.basemapId}
      basemapOpacity={resolved.opacity}
    >
      <MapLayerSync projectId={project.id} />
      <ViewportPersistence projectId={project.id} />
      <ZoomToExtent request={zoomTo} />
      <SelectionHighlight projectId={project.id} />
      {identifyEnabled && <IdentifyControl activeLayerId={activeLayer?.id ?? null} />}
      {/* Top right, immediately left of the zoom stack (`right-11` clears its 28px
          column plus a gap): the top left corner is where the measurement readout
          appears, and the two must not sit on top of each other. */}
      <div className="absolute top-2 right-11 z-10">
        <BasemapControl projectId={project.id} project={project} activeLayer={activeLayer} />
      </div>
      {children}
      <MapControls />
    </MapCanvas>
  )
}
