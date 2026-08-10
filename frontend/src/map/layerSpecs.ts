import type { FilterSpecification, LayerSpecification } from 'maplibre-gl'
import type { GeometryType, LayerSummary } from '@/api/layers'

/**
 * Prefix for every MapLibre source/layer id this module manages, so `syncLayers`
 * can tell "our" layers apart from the basemap when walking `map.getStyle()`.
 */
export const MANAGED_PREFIX = 'hgis-layer-'

/** The MVT layer name inside every tile (contract section 1) -- constant, not the display name. */
const TILE_SOURCE_LAYER = 'layer'

export function sourceIdFor(layerId: string): string {
  return `${MANAGED_PREFIX}${layerId}`
}

/**
 * Tile URL template MapLibre substitutes {z}/{x}/{y} into. The version query param
 * makes the URL change whenever data or style changes, which is what makes a plain
 * cache-immutable response (contract section 1) safe.
 */
export function buildTileUrl(layer: Pick<LayerSummary, 'id' | 'dataVersion' | 'styleVersion'>): string {
  return `/api/layers/${layer.id}/tiles/{z}/{x}/{y}.mvt?v=${layer.dataVersion}.${layer.styleVersion}`
}

/**
 * Bottom-to-top order of the MapLibre layer ids that back one catalog layer. A
 * GEOMETRY layer (mixed geometries) needs three sublayers on the same source,
 * filtered by geometry-type; every other geometry type needs exactly one.
 *
 * Both `addLayerToMap` (creation order) and `applyOrder` (moveLayer order) rely on
 * this array being bottom-to-top so polygons never obscure lines or points.
 */
export function layerIdsFor(layerId: string, geometryType: GeometryType): string[] {
  const base = sourceIdFor(layerId)
  if (geometryType === 'GEOMETRY') {
    return [`${base}-polygon`, `${base}-line`, `${base}-point`]
  }
  return [`${base}-render`]
}

const CIRCLE_PAINT = {
  'circle-radius': 3,
  'circle-color': '#404040',
  'circle-stroke-width': 1,
  'circle-stroke-color': '#fafafa',
} as const

const LINE_PAINT = {
  'line-color': '#404040',
  'line-width': 1.25,
} as const

const FILL_PAINT = {
  'fill-color': '#404040',
  'fill-opacity': 0.25,
  'fill-outline-color': '#262626',
} as const

const GEOMETRY_FILTERS: Record<'point' | 'line' | 'polygon', FilterSpecification> = {
  point: ['==', ['geometry-type'], 'Point'],
  line: ['==', ['geometry-type'], 'LineString'],
  polygon: ['==', ['geometry-type'], 'Polygon'],
}

/**
 * Builds the MapLibre layer objects for one catalog layer, in the same bottom-to-top
 * order as `layerIdsFor`. Design is deliberately monochrome (project convention, no
 * signal colors) -- per-layer styling is reserved for phase 7 (`LayerDetail.style`).
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
