import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { Place } from '@/api/places'
import { layerListQuery, type LayerSummary } from '@/api/layers'
import type { ProjectDetail } from '@/api/projects'
import { BasemapControl } from './BasemapControl'
import { distinctVisibleAttributions } from './geoportalAttribution'
import { computeInitialView } from './initialView'
import { MapCanvas } from './MapCanvas'
import { MapLayerSync } from './MapLayerSync'
import { MapViewportTracker } from './MapViewportTracker'
import { placeExtent } from './placeExtent'
import { PlaceMarker } from './PlaceMarker'
import { ViewportPersistence } from './ViewportPersistence'
import { ZoomToExtent, type ZoomRequest } from './ZoomToExtent'
import { IdentifyControl } from './IdentifyControl'
import { MapImageControl } from './imageExport/MapImageControl'
import { resolveBasemapSettings } from './resolveBasemapSettings'
import { SelectionHighlight } from './SelectionHighlight'
import { MapControls } from './controls/MapControls'
import { PlaceSearchControl } from './controls/PlaceSearchControl'

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

  // Own request/nonce pair, deliberately separate from `zoomTo` above: that one is the
  // layer tree's "zoom to layer", driven by the workspace route, while a place search
  // is entirely local to the map itself and has no reason to reach back into it.
  const [placeZoomTo, setPlaceZoomTo] = useState<ZoomRequest | null>(null)
  const [selectedPlace, setSelectedPlace] = useState<[number, number] | null>(null)

  function handlePlaceSelect(place: Place) {
    setSelectedPlace([place.lng, place.lat])
    setPlaceZoomTo((previous) => ({
      extent: placeExtent(place.lng, place.lat, place.kind),
      nonce: (previous?.nonce ?? 0) + 1,
    }))
  }

  // Already in the cache from `MapLayerSync`'s own fetch of the same query -- this is
  // what turns the layer list into the licence notices `MapCanvas` has to show next to
  // the basemap's own attribution (CONTRACT.md phase 23, section 11.7).
  const { data: layers } = useQuery(layerListQuery(project.id))
  const geoportalAttributions = distinctVisibleAttributions(layers ?? [])

  return (
    <MapCanvas
      initialView={computeInitialView(project)}
      basemapId={resolved.basemapId}
      basemapOpacity={resolved.opacity}
      geoportalAttributions={geoportalAttributions}
    >
      <MapLayerSync projectId={project.id} />
      <MapViewportTracker />
      <ViewportPersistence projectId={project.id} />
      <ZoomToExtent request={zoomTo} />
      <ZoomToExtent request={placeZoomTo} />
      <SelectionHighlight projectId={project.id} />
      <PlaceMarker position={selectedPlace} />
      <PlaceSearchControl onSelect={handlePlaceSelect} onClear={() => setSelectedPlace(null)} />
      {identifyEnabled && <IdentifyControl activeLayerId={activeLayer?.id ?? null} />}
      {/* Top right, immediately left of the zoom stack (`right-11` clears its 28px
          column plus a gap): the top left corner is where the measurement readout
          appears, and the two must not sit on top of each other. The image export shares
          the row -- it acts on the same thing the basemap picker does, the view on
          screen. */}
      <div className="absolute top-2 right-11 z-10 flex items-center gap-2">
        <MapImageControl projectName={project.name} layers={layers ?? []} />
        <BasemapControl projectId={project.id} project={project} activeLayer={activeLayer} />
      </div>
      {children}
      <MapControls extent={project.extent} />
    </MapCanvas>
  )
}
