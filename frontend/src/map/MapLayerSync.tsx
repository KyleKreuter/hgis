import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { Map as MapLibreMap } from 'maplibre-gl'
import { layerListQuery } from '@/api/layers'
import { useMap } from './MapContext'
import { type AppliedLayer, syncMapLayers } from './syncLayers'

interface MapLayerSyncProps {
  projectId: string
}

/**
 * Renders nothing. On every change to the project's layer list -- the TanStack
 * Query cache from `layerListQuery` is the "store"; a future layer tree panel
 * toggling visibility via `useUpdateLayer` writes into the very same cache -- it
 * diffs the desired state against what is actually on the map and applies the
 * difference. See `syncMapLayers` for the diff itself.
 *
 * Gated on `isLoaded`: calling addSource/addLayer before MapLibre's `load` event
 * throws. Also silently no-ops with an empty layer list, so the map starts clean
 * before track C's tile endpoint exists.
 */
export function MapLayerSync({ projectId }: MapLayerSyncProps) {
  const { mapRef, isLoaded } = useMap()
  const { data: layers } = useQuery(layerListQuery(projectId))
  const appliedRef = useRef(new Map<string, AppliedLayer>())
  // Bookkeeping is only valid for the map instance it was recorded against. React
  // 19 StrictMode's double-invoke of MapCanvas's effect cannot outrun this in
  // practice (the map cannot fire `load` before the synchronous cleanup+remount
  // pass completes), but a real map recreation is cheap to guard regardless.
  const lastMapRef = useRef<MapLibreMap | null>(null)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    if (map !== lastMapRef.current) {
      appliedRef.current.clear()
      lastMapRef.current = map
    }

    syncMapLayers(map, layers ?? [], appliedRef.current)
  }, [mapRef, isLoaded, layers])

  return null
}
