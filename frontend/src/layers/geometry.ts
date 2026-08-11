import type { GeometryType } from '@/api/layers'

/**
 * Shared by the layer tree and the import preview so a geometry is named the same way
 * before and after the import -- a layer that announced "Flächen" in the preview must
 * not turn up as something else in the tree.
 */
export const GEOMETRY_LABELS: Record<GeometryType, string> = {
  MULTIPOLYGON: 'Flächen',
  MULTILINESTRING: 'Linien',
  MULTIPOINT: 'Punkte',
  GEOMETRY: 'gemischte Geometrien',
}
