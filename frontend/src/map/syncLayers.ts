import type {
  AddLayerObject,
  LayerSpecification,
  Map as MapLibreMap,
  Source,
  VectorSourceSpecification,
} from 'maplibre-gl'
import type { LayerSummary } from '@/api/layers'
import { styleToMapLibre } from '@/styling/styleToMapLibre'
import { buildTileUrl, sourceIdFor } from './layerSpecs'

/**
 * The subset of maplibregl.Map this module touches. Kept narrow and structural on
 * purpose: a real `maplibregl.Map` satisfies it without adapting, and a test can
 * hand in a plain object of `vi.fn()`s instead of standing up WebGL.
 */
export type MapLike = Pick<
  MapLibreMap,
  | 'addSource'
  | 'removeSource'
  | 'getSource'
  | 'addLayer'
  | 'removeLayer'
  | 'getLayer'
  | 'moveLayer'
  | 'setLayoutProperty'
  | 'setPaintProperty'
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
  zIndex: number
  /**
   * The MapLibre layer objects as they were handed to `addLayer`. Kept whole, not
   * summarised: the next run diffs against them to decide between updating a paint
   * property in place and rebuilding the layers.
   */
  specs: LayerSpecification[]
}

function toApplied(layer: LayerSummary, tileUrl: string, specs: LayerSpecification[]): AppliedLayer {
  return {
    geometryType: layer.geometryType,
    tileUrl,
    minZoom: layer.minZoom,
    maxZoom: layer.maxZoom,
    zIndex: layer.zIndex,
    specs,
  }
}

function removeFromMap(map: MapLike, layerId: string, specs: LayerSpecification[]): void {
  for (const spec of specs) {
    if (map.getLayer(spec.id)) map.removeLayer(spec.id)
  }
  const sourceId = sourceIdFor(layerId)
  if (map.getSource(sourceId)) map.removeSource(sourceId)
}

function addToMap(map: MapLike, layer: LayerSummary, tileUrl: string, specs: LayerSpecification[]): void {
  const source: VectorSourceSpecification = {
    type: 'vector',
    tiles: [tileUrl],
    minzoom: layer.minZoom,
    maxzoom: layer.maxZoom,
  }
  map.addSource(sourceIdFor(layer.id), source)
  for (const spec of specs) {
    map.addLayer(spec as AddLayerObject)
  }
}

/**
 * Everything about a layer object that cannot be changed after `addLayer`. Differ in
 * any of these and the layers have to be torn down and rebuilt; differ only in paint
 * or layout and they can be updated in place.
 */
function isRebuildRequired(previous: LayerSpecification[], next: LayerSpecification[]): boolean {
  if (previous.length !== next.length) return true
  return previous.some((before, index) => {
    const after = next[index]
    return (
      before.id !== after.id ||
      before.type !== after.type ||
      before.minzoom !== after.minzoom ||
      before.maxzoom !== after.maxzoom ||
      !isSameValue('filter' in before ? before.filter : undefined, 'filter' in after ? after.filter : undefined)
    )
  })
}

/**
 * Brings the layers in line with `next` without touching the source.
 *
 * The in-place path is what keeps a colour picker usable: a paint property is a uniform
 * the renderer picks up on the next frame, while re-adding a layer re-runs layout over
 * every loaded tile. Neither path refetches tiles -- only the source does that, and it
 * is deliberately left alone here.
 */
function applySpecs(map: MapLike, previous: LayerSpecification[], next: LayerSpecification[]): void {
  if (isRebuildRequired(previous, next)) {
    for (const spec of previous) {
      if (map.getLayer(spec.id)) map.removeLayer(spec.id)
    }
    for (const spec of next) {
      map.addLayer(spec as AddLayerObject)
    }
    return
  }

  next.forEach((after, index) => {
    if (!map.getLayer(after.id)) return
    const before = previous[index]
    applyProperties(map, after.id, before.paint, after.paint, 'paint')
    applyProperties(map, after.id, before.layout, after.layout, 'layout')
  })
}

type Properties = Record<string, unknown> | undefined
type SetPaint = Parameters<MapLike['setPaintProperty']>
type SetLayout = Parameters<MapLike['setLayoutProperty']>


function applyProperties(
  map: MapLike,
  layerId: string,
  before: Properties,
  after: Properties,
  kind: 'paint' | 'layout',
): void {
  for (const name of new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})])) {
    const value = after?.[name]
    if (isSameValue(before?.[name], value)) continue
    // A property that disappeared is set to undefined, which is how MapLibre is told to
    // fall back to its own default -- removing the key alone would change nothing.
    //
    // The casts are unavoidable: MapLibre types name and value as the union over every
    // known paint/layout key, while these were walked out of an object literal. The
    // values themselves come from `styleToMapLibre`, which is typed against the very
    // same specs, so the pairing is checked where it is actually built.
    if (kind === 'paint') map.setPaintProperty(layerId, name as SetPaint[1], value as SetPaint[2])
    else map.setLayoutProperty(layerId, name as SetLayout[1], value as SetLayout[2])
  }
}

/** Paint values are scalars or expression arrays; comparing them structurally is enough. */
function isSameValue(a: unknown, b: unknown): boolean {
  return a === b || JSON.stringify(a ?? null) === JSON.stringify(b ?? null)
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
 *  - changed style or visibility -> `setPaintProperty` / `setLayoutProperty` where the
 *    layer objects are otherwise identical, a rebuild of the layers (never the source)
 *    where they are not. Neither refetches tiles, which is the point: the server keeps
 *    `styleVersion` unchanged for a pure colour change precisely so the tiles stay valid.
 *
 * Finally reorders every managed layer bottom-to-top by ascending `zIndex` via
 * `moveLayer` (no beforeId moves a layer to the very top), which also keeps a
 * GEOMETRY layer's sublayers stacked polygon -> line -> point -> label.
 */
export function syncMapLayers(map: MapLike, layers: LayerSummary[], applied: Map<string, AppliedLayer>): void {
  const desired = new Map(layers.map((layer) => [layer.id, layer]))

  for (const [layerId, state] of applied) {
    if (!desired.has(layerId)) {
      removeFromMap(map, layerId, state.specs)
      applied.delete(layerId)
    }
  }

  for (const layer of layers) {
    const tileUrl = buildTileUrl(layer)
    const sourceId = sourceIdFor(layer.id)
    const specs = styleToMapLibre(layer.style ?? null, layer, sourceId)
    const existing = applied.get(layer.id)

    if (!existing) {
      addToMap(map, layer, tileUrl, specs)
      applied.set(layer.id, toApplied(layer, tileUrl, specs))
      continue
    }

    const needsRecreate =
      existing.geometryType !== layer.geometryType ||
      existing.minZoom !== layer.minZoom ||
      existing.maxZoom !== layer.maxZoom

    if (needsRecreate) {
      removeFromMap(map, layer.id, existing.specs)
      addToMap(map, layer, tileUrl, specs)
      applied.set(layer.id, toApplied(layer, tileUrl, specs))
      continue
    }

    if (existing.tileUrl !== tileUrl) {
      const source = map.getSource(sourceId) as ReloadableSource | undefined
      if (source && typeof source.setTiles === 'function') {
        source.setTiles([tileUrl])
      }
      else {
        removeFromMap(map, layer.id, existing.specs)
        addToMap(map, layer, tileUrl, specs)
        applied.set(layer.id, toApplied(layer, tileUrl, specs))
        continue
      }
    }

    applySpecs(map, existing.specs, specs)
    applied.set(layer.id, toApplied(layer, tileUrl, specs))
  }

  for (const layer of [...layers].sort((a, b) => a.zIndex - b.zIndex)) {
    for (const spec of applied.get(layer.id)?.specs ?? []) {
      if (map.getLayer(spec.id)) map.moveLayer(spec.id)
    }
  }
}
