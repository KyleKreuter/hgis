import type { ExpressionSpecification } from 'maplibre-gl'
import { GEOMETRY_FILTERS, sourceIdFor } from '@/map/layerSpecs'
import { isOverlayLayer } from '@/map/overlays'

/**
 * Which MapLibre layers `EditingTileFilter` may filter, and what to filter them with.
 *
 * Kept apart from the component because the whole bug was in these two decisions and
 * neither of them needs a map to be checked. The id prefix alone is not the answer: the
 * selection highlight lives on the same source under the same prefix (`SelectionHighlight`
 * adds `hgis-layer-<id>-selected`), and its own `['in', ['id'], …]` filter is what says
 * which features are highlighted. Overwriting that made the highlight cover the whole
 * layer, and it stayed that way until the next selection change.
 */

/**
 * Suffix -> the filter the sublayer carries when nothing is being edited.
 *
 * Narrowed to `ExpressionSpecification`: `GEOMETRY_FILTERS` is declared as the wider
 * `FilterSpecification`, which also covers MapLibre's legacy filter syntax, and only an
 * expression can be combined with another one under `all`. The three values are
 * expressions -- `['==', ['geometry-type'], …]`, written out in `map/layerSpecs.ts`.
 */
const BASE_FILTERS: readonly (readonly [string, ExpressionSpecification])[] = [
  ['-polygon', GEOMETRY_FILTERS.polygon as ExpressionSpecification],
  ['-line', GEOMETRY_FILTERS.line as ExpressionSpecification],
  ['-point', GEOMETRY_FILTERS.point as ExpressionSpecification],
]

/**
 * The layers that draw `layerId`'s tiles, out of every layer id in the style.
 *
 * Everything under the layer's own id prefix, minus the overlays -- `isOverlayLayer` is
 * the shared rule for what an overlay is (`map/overlays.ts`), so the selection highlight
 * is recognised here by exactly the same test that keeps it stacked on top.
 */
export function editableTileLayerIds(
  styleLayerIds: readonly string[],
  layerId: string,
): string[] {
  const prefix = sourceIdFor(layerId)
  return styleLayerIds.filter((id) => id.startsWith(prefix) && !isOverlayLayer(id))
}

/**
 * The filter a tile layer carries when nothing is hidden.
 *
 * `null` means unfiltered, which is right for every layer but a GEOMETRY layer's three
 * sublayers: those exist only to split one mixed source by geometry type, and dropping
 * that filter draws points, lines and polygons through all three of them at once.
 */
export function baseTileFilter(tileLayerId: string): ExpressionSpecification | null {
  const match = BASE_FILTERS.find(([suffix]) => tileLayerId.endsWith(suffix))
  return match ? match[1] : null
}

/**
 * What to set on `tileLayerId` while `hidden` is being edited.
 *
 * The features being edited are drawn by the drawing tool, so the tiles have to stop
 * drawing their pre-edit version -- but only in addition to whatever the layer already
 * filtered on, never instead of it.
 *
 * `['id']`, not `['get','fid']`: fid is the MVT feature id and never appears among the
 * properties.
 */
export function tileFilterWhileEditing(
  tileLayerId: string,
  hidden: readonly number[],
): ExpressionSpecification | null {
  const base = baseTileFilter(tileLayerId)
  if (hidden.length === 0) return base
  const hide: ExpressionSpecification = ['!', ['in', ['id'], ['literal', [...hidden]]]]
  return base ? ['all', base, hide] : hide
}
