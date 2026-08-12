import type { AddLayerObject, Map as MapLibreMap } from 'maplibre-gl'
import { isBasemapId, type BasemapDefinition } from './basemap'

/**
 * The subset of maplibregl.Map this module touches -- narrow and structural on
 * purpose, exactly like `syncLayers`' `MapLike`: a real `maplibregl.Map` satisfies it
 * without adapting, and a test can hand in a plain object instead of standing up WebGL.
 */
export type BasemapMapLike = Pick<
  MapLibreMap,
  'getStyle' | 'addSource' | 'removeSource' | 'getSource' | 'addLayer' | 'getLayer' | 'removeLayer'
>

/** The subset `applyBasemapOpacity` touches -- a plain read plus one paint write. */
export type BasemapOpacityMapLike = Pick<MapLibreMap, 'getStyle' | 'getLayer' | 'setPaintProperty'>

/**
 * Swaps the basemap on a live map, in place.
 *
 * Deliberately not `map.setStyle()`: that replaces the whole style document and would
 * take the data layers `MapLayerSync` added imperatively with it (as well as the
 * self-hosted glyph URL), leaving the callers to restore them through a
 * `transformStyle` callback. Every basemap in the catalog is nothing but raster
 * sources plus raster layers, so removing the old ones and adding the new ones below
 * the first foreign layer achieves the same result while touching nothing else --
 * no re-initialisation, no reload of the vector tiles, no flash of an empty map.
 *
 * @returns whether anything changed; false when the requested basemap is already on
 *   the map, which is what makes it safe to call from an effect on every render.
 */
export function applyBasemap(map: BasemapMapLike, next: BasemapDefinition): boolean {
  const style = map.getStyle()
  const currentLayerIds = (style?.layers ?? []).filter((layer) => isBasemapId(layer.id)).map((layer) => layer.id)
  const nextLayerIds = next.layers.map((layer) => layer.id)

  if (isSameOrder(currentLayerIds, nextLayerIds)) return false

  for (const layerId of currentLayerIds) {
    if (map.getLayer(layerId)) map.removeLayer(layerId)
  }
  // After the layers, never before: MapLibre refuses to remove a source that a layer
  // still references.
  for (const sourceId of Object.keys(style?.sources ?? {}).filter(isBasemapId)) {
    if (map.getSource(sourceId)) map.removeSource(sourceId)
  }

  for (const [sourceId, source] of Object.entries(next.sources)) {
    if (!map.getSource(sourceId)) map.addSource(sourceId, source)
  }
  // Read the style again -- the removals above already changed it. Everything that is
  // not ours is a data layer and has to stay on top of the background.
  const beforeId = (map.getStyle()?.layers ?? []).find((layer) => !isBasemapId(layer.id))?.id
  for (const layer of next.layers) {
    map.addLayer({ ...layer } as AddLayerObject, beforeId)
  }

  return true
}

function isSameOrder(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index])
}

/**
 * Sets the background map's opacity on a live map, in place.
 *
 * Separate from `applyBasemap` on purpose: that function only swaps layers when the
 * basemap itself changes, and returns early otherwise (CONTRACT.md -- without the early
 * return the map would flicker on every render). The opacity, though, can change on its
 * own while the same basemap stays on screen, so it needs its own write that runs
 * independently of that early return. `raster-opacity` is set through `setPaintProperty`
 * rather than by re-adding the layer, which is what lets the two stay independent: the
 * variant's own `raster-saturation`/`raster-brightness-*`/`raster-contrast` (see
 * `basemap.ts`) are untouched.
 *
 * A no-op for "no basemap": there is no raster layer to carry an opacity.
 */
export function applyBasemapOpacity(map: BasemapOpacityMapLike, opacity: number): void {
  const style = map.getStyle()
  for (const layer of style?.layers ?? []) {
    if (isBasemapId(layer.id) && map.getLayer(layer.id)) {
      map.setPaintProperty(layer.id, 'raster-opacity', opacity)
    }
  }
}
