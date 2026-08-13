import type { GeometryType } from '@/api/layers'

/**
 * The drawing tools and the rule for which ones a layer accepts.
 *
 * Their own file rather than `DrawController`'s: a module that exports both a component
 * and something else loses fast refresh for the whole file, and these two are imported by
 * `EditToolbar` and `useEditSession` anyway -- neither of which wants the controller.
 */
export type DrawTool = 'select' | 'point' | 'linestring' | 'polygon'

/** Which drawing tools a layer can accept. A typed column cannot hold another kind. */
export function toolsFor(geometryType: GeometryType): DrawTool[] {
  switch (geometryType) {
    case 'MULTIPOINT':
      return ['select', 'point']
    case 'MULTILINESTRING':
      return ['select', 'linestring']
    case 'MULTIPOLYGON':
      return ['select', 'polygon']
    default:
      return ['select', 'point', 'linestring', 'polygon']
  }
}
