import type {
  CircleLayerSpecification,
  ExpressionSpecification,
  FillLayerSpecification,
  LayerSpecification,
  LineLayerSpecification,
  SymbolLayerSpecification,
} from 'maplibre-gl'
import type { LayerSummary } from '@/api/layers'
import { GEOMETRY_FILTERS, TILE_SOURCE_LAYER, layerIdsFor, layerSpecsFor } from '@/map/layerSpecs'
import { DEFAULT_FILL, DEFAULT_LINE, DEFAULT_MARKER, primaryColorOf, roleFor } from './defaults'
import type {
  FillSymbol,
  LabelStyle,
  LayerStyle,
  LayerSymbol,
  LineSymbol,
  MarkerSymbol,
  Renderer,
} from './types'

/**
 * Maps the stored style schema onto MapLibre layer objects. Pure: same input, same
 * output, no map instance involved -- which is what makes the snapshot tests below it
 * worth anything and keeps the schema itself free of MapLibre concepts.
 *
 * Returned bottom-to-top, in the same order as `layerIdsFor` -- `syncLayers` depends
 * on that for both creation order and `moveLayer`.
 */
export function styleToMapLibre(
  style: LayerStyle | null,
  layer: LayerSummary,
  sourceId: string,
): LayerSpecification[] {
  // The unstyled path is the literal one from `layerSpecs`, not a re-derivation: every
  // layer that has never been styled must keep looking exactly as it did.
  if (!style) return layerSpecsFor(layer, sourceId)

  const labels = activeLabels(style)
  const ids = layerIdsFor(layer.id, layer.geometryType, { labeled: labels !== null })
  const [minzoom, maxzoom] = zoomRange(layer, style)
  const common = {
    source: sourceId,
    'source-layer': TILE_SOURCE_LAYER,
    minzoom,
    maxzoom,
    layout: { visibility: layer.visible ? ('visible' as const) : ('none' as const) },
  }

  const specs: LayerSpecification[] = []
  if (layer.geometryType === 'GEOMETRY') {
    const [polygonId, lineId, pointId] = ids
    specs.push({ id: polygonId, type: 'fill', filter: GEOMETRY_FILTERS.polygon, paint: fillPaint(style), ...common })
    specs.push({ id: lineId, type: 'line', filter: GEOMETRY_FILTERS.line, paint: linePaint(style), ...common })
    specs.push({ id: pointId, type: 'circle', filter: GEOMETRY_FILTERS.point, paint: circlePaint(style), ...common })
  }
  else {
    specs.push(geometrySpec(ids[0], layer, style, common))
  }

  if (labels) {
    specs.push({
      id: ids[ids.length - 1],
      type: 'symbol',
      ...common,
      // Text below its own minimum zoom is not hidden but simply never requested, so
      // the labels' own threshold narrows the layer's rather than replacing it.
      minzoom: Math.max(minzoom, labels.minZoom),
      layout: { ...common.layout, ...labelLayout(labels, layer) },
      paint: labelPaint(labels),
    } satisfies SymbolLayerSpecification)
  }

  return specs
}

type Common = {
  source: string
  'source-layer': string
  minzoom: number
  maxzoom: number
  layout: { visibility: 'visible' | 'none' }
}

function geometrySpec(id: string, layer: LayerSummary, style: LayerStyle, common: Common): LayerSpecification {
  const role = roleFor(layer.geometryType)
  if (role === 'point') {
    return { id, type: 'circle', paint: circlePaint(style), ...common } satisfies CircleLayerSpecification
  }
  if (role === 'line') {
    return { id, type: 'line', paint: linePaint(style), ...common } satisfies LineLayerSpecification
  }
  return { id, type: 'fill', paint: fillPaint(style), ...common } satisfies FillLayerSpecification
}

/** Labels only count as active with a field to read; an empty one would render nothing. */
function activeLabels(style: LayerStyle): LabelStyle | null {
  const labels = style.labels
  return labels && labels.enabled && labels.field ? labels : null
}

function zoomRange(layer: LayerSummary, style: LayerStyle): [number, number] {
  const min = Math.max(layer.minZoom, style.minZoom ?? 0)
  // The style can only narrow the layer's range, never widen it past what the source
  // actually serves. Never below `min`, which MapLibre would reject outright.
  return [min, Math.max(min, Math.min(layer.maxZoom, style.maxZoom ?? 22))]
}

// -- paint ------------------------------------------------------------------------

function fillPaint(style: LayerStyle): FillLayerSpecification['paint'] {
  return {
    'fill-color': dataDriven(style.renderer, asFill, (symbol) => symbol.fillColor),
    'fill-opacity': dataDriven(style.renderer, asFill, (symbol) =>
      effectiveOpacity(symbol.fillOpacity * style.opacity),
    ),
    // MapLibre's fill layer draws a hairline outline and nothing else -- `outlineWidth`
    // has no counterpart and is not honoured. A wider outline would need a second line
    // layer per catalog layer, which is out of scope here.
    'fill-outline-color': dataDriven(style.renderer, asFill, (symbol) => symbol.outlineColor),
  }
}

function linePaint(style: LayerStyle): LineLayerSpecification['paint'] {
  // `line-dasharray` is not data-driven in MapLibre, so one pattern has to stand for the
  // whole layer: the single symbol, or the fallback of a classified renderer.
  const dashArray = asLine(representativeSymbol(style.renderer)).dashArray
  return {
    'line-color': dataDriven(style.renderer, asLine, (symbol) => symbol.color),
    'line-width': dataDriven(style.renderer, asLine, (symbol) => symbol.width),
    // Only written when it differs from MapLibre's own default, so an unmodified style
    // produces byte-for-byte the same paint object as no style at all.
    ...(style.opacity < 1 ? { 'line-opacity': effectiveOpacity(style.opacity) } : {}),
    ...(dashArray && dashArray.length > 0 ? { 'line-dasharray': dashArray } : {}),
  }
}

function circlePaint(style: LayerStyle): CircleLayerSpecification['paint'] {
  return {
    // `size` is the radius in pixels, not the diameter -- that is what makes the default
    // symbol identical to the unstyled `circle-radius: 3`.
    'circle-radius': dataDriven(style.renderer, asMarker, (symbol) => symbol.size),
    'circle-color': dataDriven(style.renderer, asMarker, (symbol) => symbol.fillColor),
    'circle-stroke-width': dataDriven(style.renderer, asMarker, (symbol) => symbol.strokeWidth),
    'circle-stroke-color': dataDriven(style.renderer, asMarker, (symbol) => symbol.strokeColor),
    ...(style.opacity < 1
      ? {
          'circle-opacity': effectiveOpacity(style.opacity),
          'circle-stroke-opacity': effectiveOpacity(style.opacity),
        }
      : {}),
  }
}

function labelLayout(labels: LabelStyle, layer: LayerSummary): SymbolLayerSpecification['layout'] {
  return {
    'text-field': ['get', labels.field],
    'text-font': LABEL_FONT,
    'text-size': labels.size,
    'text-allow-overlap': labels.allowOverlap,
    // Street names read along the line, everything else sits on the centroid.
    'symbol-placement': layer.geometryType === 'MULTILINESTRING' ? 'line' : 'point',
  }
}

function labelPaint(labels: LabelStyle): SymbolLayerSpecification['paint'] {
  return {
    'text-color': labels.color,
    'text-halo-color': labels.haloColor,
    'text-halo-width': labels.haloWidth,
  }
}

/**
 * The font stack the label layer asks for. It has to exist in the glyph source
 * declared on the basemap style (`map/basemap.ts`) -- without glyphs MapLibre renders
 * no text at all and only says so on the error event.
 */
export const LABEL_FONT = ['Noto Sans Regular']

// -- symbol resolution ------------------------------------------------------------

/**
 * A symbol always renders in the role its sublayer demands, not in the one its `kind`
 * names. A GEOMETRY layer draws the very same category symbol as polygon, line and
 * marker; only its colour carries across, everything else falls back to the default
 * for that role.
 */
function asFill(symbol: LayerSymbol): FillSymbol {
  if (symbol.kind === 'fill') return symbol
  return { ...DEFAULT_FILL, fillColor: primaryColorOf(symbol) }
}

function asLine(symbol: LayerSymbol): LineSymbol {
  if (symbol.kind === 'line') return symbol
  return { ...DEFAULT_LINE, color: primaryColorOf(symbol) }
}

function asMarker(symbol: LayerSymbol): MarkerSymbol {
  if (symbol.kind === 'marker') return symbol
  // The stroke stays the default light halo: it separates the marker from whatever is
  // beneath it, it is not part of the category's identity the way the fill is.
  return { ...DEFAULT_MARKER, fillColor: primaryColorOf(symbol) }
}

function representativeSymbol(renderer: Renderer): LayerSymbol {
  return renderer.type === 'single' ? renderer.symbol : renderer.fallbackSymbol
}

// -- data-driven values -----------------------------------------------------------

type PaintValue<T> = T | ExpressionSpecification

/**
 * Turns one property of a symbol into either a constant or a data-driven expression,
 * depending on the renderer. Every paint property goes through here, so colour, size
 * and width are classified by exactly the same rules and none of them can be forgotten.
 *
 * Collapses to a constant whenever every branch would produce the same value -- a
 * `match` over twenty categories that all have width 1.25 is pointless work for the
 * renderer, and a constant is what `syncLayers` can update via `setPaintProperty`.
 */
function dataDriven<R, T extends string | number>(
  renderer: Renderer,
  resolve: (symbol: LayerSymbol) => R,
  pick: (resolved: R) => T,
): PaintValue<T> {
  const valueOf = (symbol: LayerSymbol) => pick(resolve(symbol))

  if (renderer.type === 'single') return valueOf(renderer.symbol)

  const fallback = valueOf(renderer.fallbackSymbol)
  return renderer.type === 'categorized'
    ? matchExpression(renderer.field, renderer.categories ?? [], valueOf, fallback)
    : stepExpression(renderer.field, renderer.classes ?? [], valueOf, fallback)
}

function matchExpression<T extends string | number>(
  field: string,
  categories: { value: string | number | null; symbol: LayerSymbol }[],
  valueOf: (symbol: LayerSymbol) => T,
  fallback: T,
): PaintValue<T> {
  const branches: (string | number | T)[] = []
  const outputs: T[] = []
  const seen = new Set<string | number>()

  for (const category of categories) {
    // Objects without a value cannot be a branch label -- and do not need to be:
    // MapLibre returns the default for a null input, so they land on the fallback.
    if (category.value === null) continue
    // MapLibre rejects the whole expression over a single non-integer numeric label;
    // skipping it costs one category instead of the entire layer's colouring.
    if (typeof category.value === 'number' && !Number.isSafeInteger(category.value)) continue
    // Duplicate labels are a parse error too, so the first definition wins.
    if (seen.has(category.value)) continue

    seen.add(category.value)
    const output = valueOf(category.symbol)
    branches.push(category.value, output)
    outputs.push(output)
  }

  if (outputs.length === 0 || outputs.every((value) => value === fallback)) return fallback
  return ['match', ['get', field], ...branches, fallback] as unknown as ExpressionSpecification
}

function stepExpression<T extends string | number>(
  field: string,
  classes: { min: number; symbol: LayerSymbol }[],
  valueOf: (symbol: LayerSymbol) => T,
  fallback: T,
): PaintValue<T> {
  if (classes.length === 0) return fallback

  const first = valueOf(classes[0].symbol)
  const outputs: T[] = [first]
  const stops: (number | T)[] = []
  let previous = classes[0].min

  for (const styleClass of classes.slice(1)) {
    // Quantile breaks repeat when a value dominates the column, and `step` rejects a
    // stop list that is not strictly ascending -- such a class is simply empty.
    if (!(styleClass.min > previous)) continue
    const output = valueOf(styleClass.symbol)
    stops.push(styleClass.min, output)
    outputs.push(output)
    previous = styleClass.min
  }

  if (outputs.every((value) => value === fallback)) return fallback

  const classified =
    stops.length === 0 ? first : (['step', ['get', field], first, ...stops] as unknown as ExpressionSpecification)
  // `step` insists on a number and errors out on a missing value, which would drop the
  // property to MapLibre's own default rather than to the fallback symbol.
  return ['case', ['has', field], classified, fallback] as unknown as ExpressionSpecification
}

/**
 * Rounded to three places: multiplying two opacities gives things like
 * 0.36000000000000004, and that would show up verbatim in every diff and snapshot
 * without being any more accurate.
 */
function effectiveOpacity(value: number): number {
  return Math.round(Math.min(1, Math.max(0, value)) * 1000) / 1000
}
