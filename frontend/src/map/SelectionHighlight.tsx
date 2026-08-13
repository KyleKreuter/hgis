import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import type {
  CircleLayerSpecification,
  FillLayerSpecification,
  LineLayerSpecification,
} from 'maplibre-gl'

import { isVectorLayer, layerListQuery, type VectorLayerSummary } from '@/api/layers'
import { useSelection } from '@/state/selection'
import { useMap } from './MapContext'
import { sourceIdFor } from './layerSpecs'
import { SELECTION_LAYER_SUFFIX, raiseOverlays } from './overlays'

/**
 * The three layer kinds a highlight can be. Narrower than MapLibre's LayerSpecification
 * union, which also covers background layers -- those carry neither `source` nor
 * `filter`, so the wider union does not type-check where both are read.
 */
type HighlightSpec = FillLayerSpecification | LineLayerSpecification | CircleLayerSpecification

/**
 * Shared with `overlays`, which recognises the highlight by exactly this suffix and
 * keeps it above the data layers whenever the catalog is reconciled.
 */
const HIGHLIGHT_SUFFIX = SELECTION_LAYER_SUFFIX

/**
 * `fid` is the MVT feature id, not an attribute.
 *
 * `ST_AsMVT(tile, 'layer', 4096, 'geom', 'fid')` names fid as the id column, so it does
 * not appear among the properties. Expressions therefore have to read `['id']`;
 * `['get', 'fid']` returns null for every feature and the filter silently matches
 * nothing -- no error, just an empty highlight.
 */
function highlightFilter(fids: number[]): HighlightSpec['filter'] {
  return ['in', ['id'], ['literal', fids]]
}

function highlightSpecs(layer: VectorLayerSummary, fids: number[]): HighlightSpec[] {
  const source = sourceIdFor(layer.id)
  const common = {
    source,
    'source-layer': 'layer',
    filter: highlightFilter(fids),
  } as const

  const id = `${source}${HIGHLIGHT_SUFFIX}`
  switch (layer.geometryType) {
    case 'MULTIPOINT':
      return [{
        id, type: 'circle', ...common,
        paint: {
          'circle-radius': 5,
          'circle-color': '#0f172a',
          'circle-stroke-width': 2,
          'circle-stroke-color': '#fafafa',
        },
      }]
    case 'MULTILINESTRING':
      return [{
        id, type: 'line', ...common,
        paint: { 'line-color': '#0f172a', 'line-width': 3 },
      }]
    default:
      // Fill plus outline: a fill alone is hard to see against the layer's own fill,
      // and an outline alone disappears on small polygons.
      return [
        { id, type: 'fill', ...common, paint: { 'fill-color': '#0f172a', 'fill-opacity': 0.35 } },
        {
          id: `${id}-outline`, type: 'line', ...common,
          paint: { 'line-color': '#0f172a', 'line-width': 2 },
        },
      ]
  }
}

/**
 * Renders nothing. Draws the current selection on top of its layer.
 *
 * Kept out of `syncMapLayers` on purpose: that one reconciles the catalog, which is
 * server state, while a selection is a purely local thing that changes on every click.
 * Mixing them would re-run the whole layer diff for something that only ever needs a
 * filter update.
 */
export function SelectionHighlight({ projectId }: { projectId: string }) {
  const { mapRef, isLoaded } = useMap()
  const { data: layers } = useQuery(layerListQuery(projectId))
  const selectedLayerId = useSelection((state) => state.layerId)
  const selected = useSelection((state) => state.selected)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    const fids = [...selected]
    const layer = layers?.find((entry) => entry.id === selectedLayerId)
    // A Kartenbild is never selectable in the first place (Identify and the rectangle
    // tool both stand down for it), but this stays defensive rather than assuming that
    // holds forever.
    const specs = layer && isVectorLayer(layer) && fids.length > 0 ? highlightSpecs(layer, fids) : []
    const wanted = new Set(specs.map((spec) => spec.id))

    // Remove highlights that no longer apply -- including the previous layer's, when the
    // selection moved to a different layer.
    for (const existing of map.getStyle().layers) {
      if (existing.id.includes(HIGHLIGHT_SUFFIX) && !wanted.has(existing.id)) {
        map.removeLayer(existing.id)
      }
    }

    for (const spec of specs) {
      if (map.getLayer(spec.id)) {
        // Updating the filter beats remove/add: it keeps the layer above its neighbours
        // and does not make MapLibre re-evaluate the whole style for a click.
        map.setFilter(spec.id, spec.filter)
      } else if (map.getSource(spec.source)) {
        map.addLayer(spec)
      }
    }

    // A fresh `addLayer` lands on top of everything, including a running measurement.
    // One shared rule decides who is above whom -- see `overlays`.
    if (specs.length > 0) raiseOverlays(map)
  }, [mapRef, isLoaded, layers, selectedLayerId, selected])

  return null
}
