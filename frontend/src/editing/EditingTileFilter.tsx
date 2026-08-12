import { useEffect } from 'react'
import { useMap } from '@/map/MapContext'
import { dirtyFids, useEditing } from '@/state/editing'
import { baseTileFilter, editableTileLayerIds, tileFilterWhileEditing } from './tileFilter'

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
 *
 * `dirtyFids` already narrows the hidden set to features whose geometry actually changed
 * -- a property-only edit leaves the tile correct, so nothing here needs to know about
 * that distinction. It stays in the store because the store is what knows how a change
 * entered the buffer; this component only needs the resulting set of fids to hide.
 *
 * Which layers this may touch, and what it has to put back afterwards, is decided in
 * `tileFilter.ts` -- see there for why neither is simply "everything under the prefix"
 * and "no filter at all".
 */
export function EditingTileFilter({ layerId }: { layerId: string }) {
  const { mapRef, isLoaded } = useMap()
  const buffer = useEditing((state) => state.buffer)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    const hidden = dirtyFids(buffer)
    const managed = editableTileLayerIds(
      map.getStyle().layers.map((layer) => layer.id),
      layerId,
    )

    for (const id of managed) {
      if (!map.getLayer(id)) continue
      map.setFilter(id, tileFilterWhileEditing(id, hidden))
    }

    return () => {
      // The instance captured when the effect ran, not a fresh `mapRef.current` read:
      // these are the layers whose filters *this* run changed. A map that has been
      // removed meanwhile answers `getLayer` with undefined, so the loop simply finds
      // nothing left to put back.
      for (const id of managed) {
        if (map.getLayer(id)) map.setFilter(id, baseTileFilter(id))
      }
    }
  }, [mapRef, isLoaded, layerId, buffer])

  return null
}
