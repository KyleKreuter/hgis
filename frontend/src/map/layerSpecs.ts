import type { FilterSpecification, LayerSpecification } from 'maplibre-gl'
import type { GeometryType, LayerSummary } from '@/api/layers'

/**
 * Prefix for every MapLibre source/layer id this module manages, so `syncLayers`
 * can tell "our" layers apart from the basemap when walking `map.getStyle()`.
 */
export const MANAGED_PREFIX = 'hgis-layer-'

/** The MVT layer name inside every tile (contract section 1) -- constant, not the display name. */
export const TILE_SOURCE_LAYER = 'layer'

export function sourceIdFor(layerId: string): string {
  return `${MANAGED_PREFIX}${layerId}`
}

/**
 * Tile URL template MapLibre substitutes {z}/{x}/{y} into. The version query param
 * makes the URL change whenever data, style or clip changes, which is what makes a
 * plain cache-immutable response (contract section 1) safe.
 *
 * `clipVersion` rides along for the same reason `styleVersion` does: the tile content
 * now depends on whether a mask applies to this layer, on the mask's own geometries,
 * and on this layer's position relative to it (CONTRACT.md phase 19) -- none of which
 * `dataVersion`/`styleVersion` alone would catch. It is always appended, even at its
 * baseline `0`, so a layer gaining or losing a mask still changes the URL.
 *
 * `renderVersion` closes the gap all three of those leave open: they follow the data,
 * so none of them moves when the server's rendering changes meaning for data that
 * stayed the same (CONTRACT.md phase 21a). The server owns the value; this only passes
 * it through. Missing reads as `1`, the value it was introduced at, so a summary built
 * without it in a test fixture still produces the same URL the server would serve.
 */
export function buildTileUrl(
  layer: Pick<LayerSummary, 'id' | 'dataVersion' | 'styleVersion' | 'clipVersion' | 'renderVersion'>,
): string {
  // Absolute on purpose. MapLibre resolves tile templates in a worker, where there is
  // no document base URL to resolve a leading-slash path against -- a relative template
  // is dropped without any error event, and the layer simply stays empty.
  const origin = typeof window === 'undefined' ? '' : window.location.origin
  const clipVersion = layer.clipVersion ?? 0
  const renderVersion = layer.renderVersion ?? 1
  return `${origin}/api/layers/${layer.id}/tiles/{z}/{x}/{y}.mvt?v=${layer.dataVersion}.${layer.styleVersion}.${clipVersion}.r${renderVersion}`
}

/**
 * Bottom-to-top order of the MapLibre layer ids that back one catalog layer. A
 * GEOMETRY layer (mixed geometries) needs three sublayers on the same source,
 * filtered by geometry-type; every other geometry type needs exactly one.
 *
 * A styled layer with labels switched on adds one more id at the end: text belongs
 * above the geometry it names, and appending it keeps the array bottom-to-top.
 *
 * Both `addLayerToMap` (creation order) and `applyOrder` (moveLayer order) rely on
 * this array being bottom-to-top so polygons never obscure lines or points.
 */
export function layerIdsFor(
  layerId: string,
  geometryType: GeometryType,
  options: { labeled?: boolean } = {},
): string[] {
  const base = sourceIdFor(layerId)
  const geometryIds =
    geometryType === 'GEOMETRY'
      ? [`${base}-polygon`, `${base}-line`, `${base}-point`]
      : [`${base}-render`]
  return options.labeled ? [...geometryIds, `${base}-label`] : geometryIds
}

/**
 * The unstyled look. Exported so `styling/defaults.ts` can be pinned against it: the
 * default symbols exist to reproduce exactly this, and a test would otherwise not
 * notice the two drifting apart.
 */
export const CIRCLE_PAINT = {
  'circle-radius': 3,
  'circle-color': '#404040',
  'circle-stroke-width': 1,
  'circle-stroke-color': '#fafafa',
} as const

export const LINE_PAINT = {
  'line-color': '#404040',
  'line-width': 1.25,
} as const

export const FILL_PAINT = {
  'fill-color': '#404040',
  'fill-opacity': 0.25,
  'fill-outline-color': '#262626',
} as const

export const GEOMETRY_FILTERS: Record<'point' | 'line' | 'polygon', FilterSpecification> = {
  point: ['==', ['geometry-type'], 'Point'],
  line: ['==', ['geometry-type'], 'LineString'],
  polygon: ['==', ['geometry-type'], 'Polygon'],
}

/**
 * Builds the MapLibre layer objects for a catalog layer *without* a style, in the same
 * bottom-to-top order as `layerIdsFor`. Monochrome by project convention.
 *
 * This is the default rendering the whole styling feature falls back to, which is why
 * it stays a separate literal path: `styleToMapLibre(null, …)` returns exactly this,
 * and no layer that has never been styled may look any different than it did before.
 */
export function layerSpecsFor(layer: LayerSummary, sourceId: string): LayerSpecification[] {
  const visibility = layer.visible ? 'visible' : 'none'
  const common = {
    source: sourceId,
    'source-layer': TILE_SOURCE_LAYER,
    minzoom: layer.minZoom,
    maxzoom: layer.maxZoom,
    layout: { visibility },
  } as const

  if (layer.geometryType === 'GEOMETRY') {
    const [polygonId, lineId, pointId] = layerIdsFor(layer.id, 'GEOMETRY')
    return [
      { id: polygonId, type: 'fill', filter: GEOMETRY_FILTERS.polygon, paint: FILL_PAINT, ...common },
      { id: lineId, type: 'line', filter: GEOMETRY_FILTERS.line, paint: LINE_PAINT, ...common },
      { id: pointId, type: 'circle', filter: GEOMETRY_FILTERS.point, paint: CIRCLE_PAINT, ...common },
    ]
  }

  const [renderId] = layerIdsFor(layer.id, layer.geometryType)
  if (layer.geometryType === 'MULTIPOINT') {
    return [{ id: renderId, type: 'circle', paint: CIRCLE_PAINT, ...common }]
  }
  if (layer.geometryType === 'MULTILINESTRING') {
    return [{ id: renderId, type: 'line', paint: LINE_PAINT, ...common }]
  }
  return [{ id: renderId, type: 'fill', paint: FILL_PAINT, ...common }]
}
