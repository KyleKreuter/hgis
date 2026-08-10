import { useEffect } from 'react'
import { useMap } from '@/map/MapContext'
import { sourceIdFor } from '@/map/layerSpecs'
import { dirtyFids, useEditing } from '@/state/editing'

/**
 * Renders nothing. Hides features from the tile layers while they are being edited.
 *
 * An edited feature exists twice on screen: in the tiles, which still hold the version
 * from before the edit, and in the drawing tool, which holds the new one. Both are drawn,
 * so without this the old outline stays visible underneath every change and a deleted
 * feature does not disappear at all.
 *
 * A filter is the right tool because the tiles themselves are still valid -- nothing has
 * been saved. Refetching them would show exactly the same geometry again.
 */
export function EditingTileFilter({ layerId }: { layerId: string }) {
  const { mapRef, isLoaded } = useMap()
  const buffer = useEditing((state) => state.buffer)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    const hidden = dirtyFids(buffer)
    const managed = map
      .getStyle()
      .layers.map((layer) => layer.id)
      .filter((id) => id.startsWith(sourceIdFor(layerId)))

    for (const id of managed) {
      if (!map.getLayer(id)) continue
      // `['id']`, not `['get','fid']`: fid is the MVT feature id and never appears among
      // the properties. Null puts the layer back to unfiltered.
      map.setFilter(
        id,
        hidden.length === 0 ? null : ['!', ['in', ['id'], ['literal', hidden]]],
      )
    }

    return () => {
      const target = mapRef.current
      if (!target) return
      for (const id of managed) {
        if (target.getLayer(id)) target.setFilter(id, null)
      }
    }
  }, [mapRef, isLoaded, layerId, buffer])

  return null
}
