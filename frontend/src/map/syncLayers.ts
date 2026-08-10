import type { AddLayerObject, Map as MapLibreMap, Source, VectorSourceSpecification } from 'maplibre-gl'
import type { LayerSummary } from '@/api/layers'
import { buildTileUrl, layerIdsFor, layerSpecsFor, sourceIdFor } from './layerSpecs'

/**
 * The subset of maplibregl.Map this module touches. Kept narrow and structural on
 * purpose: a real `maplibregl.Map` satisfies it without adapting, and a test can
 * hand in a plain object of `vi.fn()`s instead of standing up WebGL.
 */
export type MapLike = Pick<
  MapLibreMap,
  'addSource' | 'removeSource' | 'getSource' | 'addLayer' | 'removeLayer' | 'getLayer' | 'moveLayer' | 'setLayoutProperty'
>

/** A source that supports the in-place tile reload `syncMapLayers` prefers. */
type ReloadableSource = Source & { setTiles?: (tiles: string[]) => unknown }

/**
 * What `syncMapLayers` believes is currently on the map for one catalog layer.
 * MapLibre does not expose the tile URL of an existing source for reading back, so
 * this is tracked by the caller (one `Map` instance, held in a ref) instead of
 * re-derived from the map on every run.
 */
export interface AppliedLayer {
  geometryType: LayerSummary['geometryType']
  tileUrl: string
  minZoom: number
  maxZoom: number
  visible: boolean
  zIndex: number
}

function toApplied(layer: LayerSummary, tileUrl: string): AppliedLayer {
  return {
    geometryType: layer.geometryType,
    tileUrl,
    minZoom: layer.minZoom,
    maxZoom: layer.maxZoom,
    visible: layer.visible,
    zIndex: layer.zIndex,
  }
}

function removeFromMap(map: MapLike, layerId: string, geometryType: LayerSummary['geometryType']): void {
  for (const id of layerIdsFor(layerId, geometryType)) {
    if (map.getLayer(id)) map.removeLayer(id)
  }
  const sourceId = sourceIdFor(layerId)
  if (map.getSource(sourceId)) map.removeSource(sourceId)
}

function addToMap(map: MapLike, layer: LayerSummary, tileUrl: string): void {
  const sourceId = sourceIdFor(layer.id)
  const source: VectorSourceSpecification = {
    type: 'vector',
    tiles: [tileUrl],
    minzoom: layer.minZoom,
    maxzoom: layer.maxZoom,
  }
  map.addSource(sourceId, source)
  for (const spec of layerSpecsFor(layer, sourceId)) {
    map.addLayer(spec as AddLayerObject)
  }
}

function setVisibility(map: MapLike, layer: LayerSummary): void {
  const value = layer.visible ? 'visible' : 'none'
  for (const id of layerIdsFor(layer.id, layer.geometryType)) {
    if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', value)
  }
}

/**
 * Applies the diff between the catalog's layer list (source of truth, from the
 * `layerListQuery` cache) and what is actually on the map right now.
 *
 * `applied` is mutated in place -- it is the caller's bookkeeping of the previous
 * run, normally a `useRef(new Map())` held for the lifetime of the map instance.
 *
 * Handles, per layer:
 *  - new layer -> addSource + addLayer(s)
 *  - removed layer (deleted, or dropped from this project) -> removeLayer(s) + removeSource
 *  - changed tile URL (data or style version bumped) -> `source.setTiles()` in place
 *    when the installed MapLibre version supports it, otherwise remove+re-add
 *  - changed min/max zoom -> source zoom bounds are immutable after creation, so
 *    remove+re-add
 *  - changed geometryType -> remove+re-add (defensive; the catalog does not allow
 *    this in practice)
 *  - changed visibility -> `setLayoutProperty`, never a source reload, so toggling
 *    a layer off and on again does not re-fetch tiles or flicker
 *
 * Finally reorders every managed layer bottom-to-top by ascending `zIndex` via
 * `moveLayer` (no beforeId moves a layer to the very top), which also keeps a
 * GEOMETRY layer's three sublayers stacked polygon -> line -> point.
 */
export function syncMapLayers(map: MapLike, layers: LayerSummary[], applied: Map<string, AppliedLayer>): void {
  const desired = new Map(layers.map((layer) => [layer.id, layer]))

  for (const [layerId, state] of applied) {
    if (!desired.has(layerId)) {
      removeFromMap(map, layerId, state.geometryType)
      applied.delete(layerId)
    }
  }

  for (const layer of layers) {
    const tileUrl = buildTileUrl(layer)
    const existing = applied.get(layer.id)

    if (!existing) {
      addToMap(map, layer, tileUrl)
      applied.set(layer.id, toApplied(layer, tileUrl))
      continue
    }

    const needsRecreate =
      existing.geometryType !== layer.geometryType ||
      existing.minZoom !== layer.minZoom ||
      existing.maxZoom !== layer.maxZoom

    if (needsRecreate) {
      removeFromMap(map, layer.id, existing.geometryType)
      addToMap(map, layer, tileUrl)
      applied.set(layer.id, toApplied(layer, tileUrl))
      continue
    }

    if (existing.tileUrl !== tileUrl) {
      const source = map.getSource(sourceIdFor(layer.id)) as ReloadableSource | undefined
      if (source && typeof source.setTiles === 'function') {
        source.setTiles([tileUrl])
      } else {
        removeFromMap(map, layer.id, layer.geometryType)
        addToMap(map, layer, tileUrl)
      }
    }

    if (existing.visible !== layer.visible) {
      setVisibility(map, layer)
    }

    applied.set(layer.id, toApplied(layer, tileUrl))
  }

  for (const layer of [...layers].sort((a, b) => a.zIndex - b.zIndex)) {
    for (const id of layerIdsFor(layer.id, layer.geometryType)) {
      if (map.getLayer(id)) map.moveLayer(id)
    }
  }
}
