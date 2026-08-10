import { useEffect, useRef } from 'react'
import { useUpdateProject } from '@/api/projects'
import { useMap } from './MapContext'

const SAVE_DEBOUNCE_MS = 2000

interface ViewportPersistenceProps {
  projectId: string
}

/**
 * Renders nothing. Persists the map's viewport to the project on `moveend`,
 * debounced by two seconds so a drag-heavy session fires one PATCH, not one per
 * frame. Listens to `moveend`, not `move` -- `move` fires continuously while
 * dragging or animating.
 */
export function ViewportPersistence({ projectId }: ViewportPersistenceProps) {
  const { mapRef, isLoaded } = useMap()
  const { mutate: updateProject } = useUpdateProject(projectId)
  // Mirrored into a ref so the effect below can stay on [mapRef, isLoaded] only --
  // re-subscribing the moveend listener on every mutate identity change would be
  // both wasteful and pointless, the ref always has the latest one.
  const updateProjectRef = useRef(updateProject)
  updateProjectRef.current = updateProject

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    let timeoutId: ReturnType<typeof setTimeout> | undefined

    const handleMoveEnd = () => {
      clearTimeout(timeoutId)
      timeoutId = setTimeout(() => {
        const center = map.getCenter()
        updateProjectRef.current({ center: [center.lng, center.lat], zoom: map.getZoom() })
      }, SAVE_DEBOUNCE_MS)
    }

    map.on('moveend', handleMoveEnd)
    return () => {
      map.off('moveend', handleMoveEnd)
      clearTimeout(timeoutId)
    }
  }, [mapRef, isLoaded])

  return null
}
