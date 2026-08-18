import type {
  CircleLayerSpecification,
  ExpressionSpecification,
  FillLayerSpecification,
  HeatmapLayerSpecification,
  LayerSpecification,
  LineLayerSpecification,
  SymbolLayerSpecification,
} from 'maplibre-gl'
import type { VectorLayerSummary } from '@/api/layers'
import { GEOMETRY_FILTERS, TILE_SOURCE_LAYER, layerIdsFor, layerSpecsFor } from '@/map/layerSpecs'
import type { FieldRange, FieldRangeState } from './classification'
import {
  COLOR_RAMPS,
  DEFAULT_FILL,
  DEFAULT_HEATMAP_INTENSITY,
  DEFAULT_HEATMAP_RADIUS,
  DEFAULT_LABELS,
  DEFAULT_LINE,
  DEFAULT_MARKER,
  colorOr,
  numberOr,
  parseHex,
  primaryColorOf,
  roleFor,
  sampleRamp,
} from './defaults'
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
  layer: VectorLayerSummary,
  sourceId: string,
  /**
   * The weight field's `min`/`max`, for a heatmap renderer that has one -- see
   * `classification.ts`'s `FieldRangeState` for what each of its three states means and
   * why a heatmap needs this parameter at all where the other renderers do not. Supplied
   * by the caller rather than fetched in here: this function stays pure and synchronous,
   * and `MapLayerSync` is what actually owns the query (`classification.ts`'s
   * `heatmapFieldRangeQuery`, `map/heatmapFieldRanges.ts`).
   */
  fieldRange?: FieldRangeState,
): LayerSpecification[] {
  // The unstyled path is the literal one from `layerSpecs`, not a re-derivation: every
  // layer that has never been styled must keep looking exactly as it did.
  if (!style) return layerSpecsFor(layer, sourceId)

  const labels = activeLabels(style)
  // Read once and defaulted: `opacity` multiplies into every paint value below, so a
  // missing one would not produce a wrong colour but NaN -- which MapLibre rejects
  // exactly as harshly as undefined, by dropping the layer.
  const opacity = numberOr(style.opacity, 1)
  const [minzoom, maxzoom] = zoomRange(layer, style)
  const common = {
    source: sourceId,
    'source-layer': TILE_SOURCE_LAYER,
    minzoom,
    maxzoom,
    layout: { visibility: layer.visible ? ('visible' as const) : ('none' as const) },
  }

  // A heatmap-styled layer is never split by geometry type -- the server hands it
  // points regardless of `layer.geometryType` (renderer contract) -- so it takes its
  // own path entirely rather than falling into the GEOMETRY/single-sublayer branch below.
  if (style.renderer.type === 'heatmap') {
    return heatmapSpecs(style.renderer, layer, opacity, common, labels, fieldRange)
  }

  const ids = layerIdsFor(layer.id, layer.geometryType, { labeled: labels !== null })

  const specs: LayerSpecification[] = []
  if (layer.geometryType === 'GEOMETRY') {
    const [polygonId, lineId, pointId] = ids
    specs.push({ id: polygonId, type: 'fill', filter: GEOMETRY_FILTERS.polygon, paint: fillPaint(style.renderer, opacity), ...common })
    specs.push({ id: lineId, type: 'line', filter: GEOMETRY_FILTERS.line, paint: linePaint(style.renderer, opacity), ...common })
    specs.push({ id: pointId, type: 'circle', filter: GEOMETRY_FILTERS.point, paint: circlePaint(style.renderer, opacity), ...common })
  }
  else {
    specs.push(geometrySpec(ids[0], layer, style.renderer, opacity, common))
  }

  if (labels) {
    specs.push({
      id: ids[ids.length - 1],
      type: 'symbol',
      ...common,
      // Text below its own minimum zoom is not hidden but simply never requested, so
      // the labels' own threshold narrows the layer's rather than replacing it.
      minzoom: Math.max(minzoom, numberOr(labels.minZoom, DEFAULT_LABELS.minZoom)),
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

function geometrySpec(id: string, layer: VectorLayerSummary, renderer: ClassicRenderer, opacity: number, common: Common): LayerSpecification {
  const role = roleFor(layer.geometryType)
  if (role === 'point') {
    return { id, type: 'circle', paint: circlePaint(renderer, opacity), ...common } satisfies CircleLayerSpecification
  }
  if (role === 'line') {
    return { id, type: 'line', paint: linePaint(renderer, opacity), ...common } satisfies LineLayerSpecification
  }
  return { id, type: 'fill', paint: fillPaint(renderer, opacity), ...common } satisfies FillLayerSpecification
}

/**
 * Every renderer except `heatmap` -- `styleToMapLibre` dispatches a heatmap renderer to
 * `heatmapSpecs` before any of the functions below ever run (`fillPaint`/`linePaint`/
 * `circlePaint`, `representativeSymbol`, `dataDriven`), so none of them need a heatmap
 * branch of their own; narrowing the parameter type here is what makes that guarantee
 * checked rather than just documented.
 */
type ClassicRenderer = Exclude<Renderer, { type: 'heatmap' }>

/**
 * A heatmap-styled layer's specs -- always exactly one render sublayer (`layerIdsFor`'s
 * `heatmap: true` path), plus a label sublayer under the same rules every other renderer
 * follows.
 */
function heatmapSpecs(
  renderer: Extract<Renderer, { type: 'heatmap' }>,
  layer: VectorLayerSummary,
  opacity: number,
  common: Common,
  labels: LabelStyle | null,
  fieldRange: FieldRangeState,
): LayerSpecification[] {
  const ids = layerIdsFor(layer.id, layer.geometryType, { labeled: labels !== null, heatmap: true })
  const specs: LayerSpecification[] = [
    { id: ids[0], type: 'heatmap', paint: heatmapPaint(renderer, opacity, fieldRange), ...common } satisfies HeatmapLayerSpecification,
  ]

  if (labels) {
    specs.push({
      id: ids[ids.length - 1],
      type: 'symbol',
      ...common,
      minzoom: Math.max(common.minzoom, numberOr(labels.minZoom, DEFAULT_LABELS.minZoom)),
      // The tile carries points only, whatever `layer.geometryType` says (renderer
      // contract) -- forcing a geometry type other than MULTILINESTRING is what keeps
      // `labelLayout` from asking for line placement text will never actually have.
      layout: { ...common.layout, ...labelLayout(labels, { ...layer, geometryType: 'MULTIPOINT' }) },
      paint: labelPaint(labels),
    } satisfies SymbolLayerSpecification)
  }

  return specs
}

/** Labels only count as active with a field to read; an empty one would render nothing. */
function activeLabels(style: LayerStyle): LabelStyle | null {
  const labels = style.labels
  return labels && labels.enabled && labels.field ? labels : null
}

function zoomRange(layer: VectorLayerSummary, style: LayerStyle): [number, number] {
  const min = Math.max(numberOr(layer.minZoom, 0), numberOr(style.minZoom, 0))
  // The style can only narrow the layer's range, never widen it past what the source
  // actually serves. Never below `min`, which MapLibre would reject outright.
  return [min, Math.max(min, Math.min(numberOr(layer.maxZoom, 22), numberOr(style.maxZoom, 22)))]
}

// -- paint ------------------------------------------------------------------------

/**
 * Last line of defence: strips members whose value came out `undefined`.
 *
 * With every member defaulted above this should never have anything to do. It stays
 * because of how unforgiving the failure is -- MapLibre answers one `undefined` paint
 * value by discarding the entire layer, not the one property, and says so only on the
 * error event. An absent property costs its MapLibre default instead, which is a
 * visible detail rather than a layer that silently disappeared.
 */
function defined<T extends object>(properties: T): T {
  const entries = Object.entries(properties).filter(([, value]) => value !== undefined)
  return Object.fromEntries(entries) as T
}

/**
 * `heatmap-radius`/`heatmap-intensity` are plain numbers, never data-driven (renderer
 * contract) -- clamped defensively so a value the panel's own `NumberInput` bounds
 * cannot enforce (a style edited by hand, or written by another client) cannot reach
 * MapLibre out of range.
 */
function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/**
 * `heatmap-weight` is what a point's field value contributes to the density MapLibre
 * accumulates underneath `heatmap-color` -- and MapLibre reads it as 0..1 (per its own
 * spec, "typically 0 to 1"). A raw field value of, say, 0..70 is not in that range: every
 * point would saturate the same way past the first few, and the heatmap would look flat
 * -- present, but not actually a heatmap of anything. Normalising against the field's own
 * `min`/`max` (`FieldRange`, sourced from `/classify`'s response, see
 * `classification.ts`'s `heatmapFieldRangeQuery`) is what restores that spread; a fixed
 * scale could not, since two heatmap fields rarely share an order of magnitude.
 *
 * This is a plain min/max stretch, not a percentile one -- deliberately: a single
 * outlier (0..10000 with everything else between 50 and 100) compresses the rest of the
 * scale toward 0, which looks like an empty heatmap rather than a wrong one. Clipping to
 * a percentile would fix that, at the cost of a second decision (what happens to the
 * values above it -- clamp to the hottest band, or drop them) that has no answer without
 * real data to look at. Left as min/max for now (team review, package 2) -- if a layer's
 * heatmap looks suspiciously empty, this stretch, not a bug, is the first thing to check.
 *
 * Falls back to a constant weight of 1 -- every point counts equally -- whenever the low
 * or the high end cannot be resolved: no field at all (density mode, the renderer
 * contract's default), the range for a freshly chosen field not having loaded yet (a
 * transient state the map recovers from on its own once the query resolves and
 * `MapLayerSync` re-runs), or a degenerate span (high not strictly greater than low, e.g.
 * every object shares one value) that would otherwise divide by zero.
 *
 * `weightMin`/`weightMax` (renderer contract, package 2 -- "Gewichtsbereich") override the
 * low/high end the automatic stretch would otherwise pick, independently of one another:
 * either, both or neither may be set. Set alone, the other end still needs `range` to fall
 * back to (`normalisationFloor`/`range.max`, exactly as before this parameter existed) --
 * so the "range not loaded yet" fallback above still applies to that end. Set for *both*
 * ends, `range` is not consulted at all: the expression built below only ever reads
 * `low`/`high`, so a heatmap with both bounds fixed renders correctly the instant the
 * style loads, without waiting on `/classify`'s field-range round trip, and keeps
 * rendering correctly through a range request that fails or comes back invalid --
 * `heatmapPaint` still swaps `heatmap-color` to the diagnostic ramp on that failure
 * (that signal is about trusting the *live* range, unrelated to whether a weight can be
 * computed), but the weight itself no longer depends on the request having succeeded.
 */
function heatmapWeight(
  field: string | null,
  range: FieldRange | undefined,
  weightMin: number | undefined,
  weightMax: number | undefined,
): PaintValue<number> {
  if (!field) return 1
  // A `range` with `max` not strictly above `min` is as unusable here as no range at all
  // -- checked once, on the raw range, so a caller-supplied `weightMin`/`weightMax` can
  // never be contaminated by it (see `hasRange`'s two uses below).
  const hasRange = range !== undefined && range.max > range.min
  const low = weightMin ?? (hasRange ? normalisationFloor(range) : undefined)
  const high = weightMax ?? (hasRange ? range.max : undefined)
  if (low === undefined || high === undefined || !(high > low)) return 1
  return [
    'case',
    // An object without this field is not the same thing as one whose value happens to
    // be low -- and MapLibre's own `to-number` does not draw that line the way its
    // signature suggests. Its fallback argument (here `low`, see `normalisationFloor`)
    // only fires for a value that fails `Number(...)`, e.g. a non-numeric string; `['get',
    // field]` evaluating to `null` (a missing property, *or* one explicitly holding
    // `null`) short-circuits the whole coercion to a literal `0` *before* the fallback is
    // ever consulted (`@maplibre/maplibre-gl-style-spec`'s `Coercion.evaluate`, the
    // `'number'` case). Fed straight into `interpolate`, that stray `0` reads as
    // wherever `0` happens to fall in *this* field's range -- the low end for an
    // all-positive range by coincidence, the hottest point of the map for an
    // all-negative one.
    //
    // `['==', ['get', field], null]` catches the absence itself, before `to-number`
    // ever gets a say, and routes it to an explicit, chosen weight instead of an
    // accidental one. Deliberately not `['!', ['has', field]]`: `has` only tells whether
    // the *key* exists on the feature, and this project's own tiles happen to omit the
    // key entirely for a `NULL` column value -- PostGIS's `ST_AsMVT` drops the property
    // rather than encoding an explicit null, confirmed by decoding the protobuf bytes of
    // a two-feature test tile, one `NULL`, one `42.0` (team review, package 2). `get`
    // returns `null` for *both* a missing key and a present key whose value is `null`,
    // so checking its result directly does not depend on that encoding choice holding --
    // a future tile source (a GeoJSON layer, say) that *does* write an explicit
    // `{"field": null}` would still be caught the same way. And unlike JavaScript's
    // `==`, MapLibre's is strict here -- confirmed by evaluating the condition in
    // isolation, not just as part of the full expression: a real `0`, `""`, `false` or
    // `NaN` reads as `false` (team review, package 2), so a genuine measurement of zero
    // is never mistaken for "no value" the way a looser check might read it.
    ['==', ['get', field], null],
    // Absent counts as "contributes nothing" -- the heatmap equivalent of not being
    // drawn at all, since a weight of 0 adds no density regardless of how many such
    // points overlap. Chosen over excluding the point via a layer `filter`: this is one
    // expression, not a second mechanism, and it needs no maintenance of its own.
    0,
    [
      'interpolate',
      ['linear'],
      ['to-number', ['get', field], low],
      low,
      0,
      high,
      1,
    ],
  ] as unknown as ExpressionSpecification
}

/**
 * The value that maps to weight 0 -- `range.min` stretched the scale from the field's own
 * observed minimum, which reads as "the same thing `null` gets" (team review, package 3
 * addendum): normalising `laenge_km`'s 0,503..6,167 against its own minimum gave the
 * *shortest real flight segment in the dataset* -- not a missing value, the one this
 * stretch exists to show -- exactly the weight `heatmapWeight` hands out for "no value at
 * all" two branches above. An object that has a value must never look like one that does
 * not.
 *
 * Anchored at 0 instead whenever every value in the range is already non-negative: for a
 * ratio-scale quantity -- a length, a share, a count, every field this has been used on so
 * far -- 0 is not just another number, it is the point the quantity itself calls
 * "nothing", the reading `CONTRACT.md`'s own example ("ein Feld mit Werten von 0 bis 70")
 * already assumes. Anchoring there instead of at the sample's minimum is what lets a
 * genuine minimum read as "a little" rather than "nothing": `laenge_km`'s shortest segment
 * now gets 0,503 / 6,167 ≈ 8 % weight, `anteil_prozent`'s smallest share 4,6 / 55,2 ≈ 8 %
 * -- present on the map, distinguishable from a null, at the cost this trades in on
 * purpose (next paragraph).
 *
 * The cost: two values close together but far from zero -- 1000 and 1005, say -- normalise
 * to 1000/1005 ≈ 0,995 and 1,0, indistinguishable on the map, where a min-anchored stretch
 * would have shown the full 0..1 spread between them. Both anchors have a price; this one
 * is chosen because the alternative's price is not occasional but structural -- a
 * min-anchored stretch puts *some* real value at weight 0 for every single field it is
 * ever used on, the sample's minimum always coincides with the stretch's own zero end by
 * construction, where the "values cluster far from zero" cost above only bites a field
 * whose meaningful range does not start near 0 in the first place (a temperature in
 * Kelvin, an id, a year -- not a length or a share).
 *
 * Left unanchored (at `range.min`, the previous behaviour, unchanged) whenever the range
 * reaches below 0: 0 stops being a usable floor the moment a value can go below it, since
 * mapping a negative value to a negative weight is outside what MapLibre's `heatmap-weight`
 * accepts ("typically 0 to 1") and clamping it to 0 would reintroduce the exact confusion
 * this function exists to remove, now between "at or below zero" and "no value" instead of
 * between "the sample's minimum" and "no value". A field whose meaningful weight can go
 * negative (a balance, a deviation from an expected value) is a genuinely different
 * question -- does a large negative value mean "very little heat" or "heat of the opposite
 * kind"? -- with no answer this function can give on its own; it is left as it was rather
 * than guessed at (team review, package 3 addendum -- flagged, not solved here).
 *
 * Confirmed the hard way, not just argued: forcing 0 unconditionally (mutation testing
 * this fix) does not merely mis-weight a range whose `max` is itself negative -- it makes
 * `heatmapWeight`'s own `interpolate` unparseable. `interpolate`'s stops must strictly
 * ascend, and `0, ..., max` descends the moment `max < 0`. A negative-only range does not
 * just deserve the old anchor; with a floor of 0 it could not produce a valid expression
 * at all.
 *
 * Only ever consulted for the low end when `weightMin` (renderer contract, package 2) is
 * *not* set -- a field whose automatic floor is wrong (`baujahr`, this function's own
 * running example) is exactly what `weightMin` exists to override by hand, this function
 * having no way to know a value is meaningful rather than "nothing" without being told.
 * Exported for `HeatmapEditor`'s legend and its bound input's placeholder, which show the
 * same automatic floor a field would get if `weightMin` were cleared -- computed once,
 * here, rather than re-derived a second time and risking the two drifting apart.
 */
export function normalisationFloor(range: FieldRange): number {
  return range.min >= 0 ? 0 : range.min
}

/** Same sample count `PaletteSelect`'s own preview swatch uses, so a ramp looks the same
 *  in the picker as it does spread across the heatmap. */
const HEATMAP_COLOR_STEPS = 6

/**
 * `heatmap-color` is keyed on MapLibre's own `["heatmap-density"]`, not on the field --
 * density is already the accumulated result of every point's weight, radius and
 * intensity, and is the one heatmap paint property that is never data-driven off a
 * feature property directly. Density 0 fades to fully transparent rather than to a fixed
 * "empty" colour, so switching ramps never needs a second colour picked just for that.
 *
 * An unknown `rampId` (a stored style predating a ramp that has since been renamed or
 * removed, or one a future client wrote that this build does not yet know) used to fall
 * back to `COLOR_RAMPS[0]` -- `blues` -- silently. That is indistinguishable from someone
 * having deliberately chosen blue, on the map and in every legend swatch alike; nothing
 * about the result says the name did not resolve. It gets `heatmapErrorColorRamp`'s hazard
 * stripes instead, the same diagnostic pattern a confirmed-broken field range already
 * falls back to (below) -- both are "this heatmap's colour cannot be trusted right now",
 * and a single unmistakable pattern for that is worth more than two a viewer would have to
 * learn to tell apart. Whoever opens the panel next to it still sees the stored (invalid)
 * name in `renderer.ramp` and can fix it there; the map itself only needs to refuse to
 * look like a legitimate choice.
 */
function heatmapColorRamp(rampId: string): ExpressionSpecification {
  const ramp = COLOR_RAMPS.find((candidate) => candidate.id === rampId)
  if (!ramp) return heatmapErrorColorRamp()
  const colors = sampleRamp(ramp, HEATMAP_COLOR_STEPS)
  const stops = colors.flatMap((color, index) => [
    index / (colors.length - 1),
    index === 0 ? toTransparent(color) : color,
  ])
  return ['interpolate', ['linear'], ['heatmap-density'], ...stops] as unknown as ExpressionSpecification
}

function toTransparent(hex: string): string {
  const [r, g, b] = parseHex(hex)
  return `rgba(${r}, ${g}, ${b}, 0)`
}

/**
 * The colour a heatmap falls back to whenever its ordinary ramp cannot be trusted --
 * either its weight field's range is *confirmed* unavailable (`FieldRangeState`'s
 * `'error'`/`'invalid'`, checked in `heatmapPaint` below), not while it is merely still
 * loading, which stays on the ordinary ramp via `heatmapColorRamp`; or `renderer.ramp`
 * itself does not name a ramp in `COLOR_RAMPS` (`heatmapColorRamp`'s own fallback, e.g. a
 * stored style referencing a ramp that has since been renamed or removed). Without this, a
 * heatmap in either state would render with a constant weight *and* an ordinary colour --
 * indistinguishable from a deliberately chosen density-mode heatmap, forever, with no way
 * to tell "finished" from "broken" short of opening the panel (team review, package 2).
 *
 * Deliberately not grey, or any other single colour: `COLOR_RAMPS` already offers `greys`
 * as a legitimate choice (`defaults.ts`), so a flat grey fallback would be invisible to
 * anyone who happens to like grey heatmaps -- the one case the signal most needs to
 * survive. Every ramp in the catalogue interpolates smoothly between two or three anchor
 * colours; alternating between two fixed, saturated ones turns that smooth gradient into
 * visible concentric bands instead -- the same "hazard stripes" convention warning tape
 * uses -- which no ramp, present or future, produces by accident. Readable on the map
 * alone, without opening the panel next to it.
 */
const HEATMAP_ERROR_BANDS = 8
const HEATMAP_ERROR_COLORS = ['#f59e0b', '#18181b'] as const // amber / near-black

function heatmapErrorColorRamp(): ExpressionSpecification {
  const stops: (number | string)[] = [0, 'rgba(0, 0, 0, 0)']
  for (let index = 1; index <= HEATMAP_ERROR_BANDS; index += 1) {
    stops.push(index / HEATMAP_ERROR_BANDS, HEATMAP_ERROR_COLORS[index % 2])
  }
  return ['interpolate', ['linear'], ['heatmap-density'], ...stops] as unknown as ExpressionSpecification
}

function heatmapPaint(
  renderer: Extract<Renderer, { type: 'heatmap' }>,
  opacity: number,
  fieldRange: FieldRangeState,
): HeatmapLayerSpecification['paint'] {
  // `'error'` (the request failed) and `'invalid'` (it succeeded with an unusable
  // result) get the same rendering treatment here -- they differ only in what the toast
  // advises (`heatmapFieldRanges.ts`'s `useHeatmapRangeErrorToasts`), not in what the map
  // shows.
  const failed = fieldRange === 'error' || fieldRange === 'invalid'
  return defined<NonNullable<HeatmapLayerSpecification['paint']>>({
    // A failed range is not a range `heatmapWeight` can use either -- it falls back to
    // the same constant weight "still loading" gets, the colour is what carries the
    // distinction.
    'heatmap-weight': heatmapWeight(renderer.field, failed ? undefined : fieldRange, renderer.weightMin, renderer.weightMax),
    'heatmap-radius': clamp(numberOr(renderer.radius, DEFAULT_HEATMAP_RADIUS), 1, 100),
    'heatmap-intensity': clamp(numberOr(renderer.intensity, DEFAULT_HEATMAP_INTENSITY), 0.1, 5),
    'heatmap-color': failed ? heatmapErrorColorRamp() : heatmapColorRamp(renderer.ramp),
    // Only written when it differs from MapLibre's own default, same convention
    // `linePaint`/`circlePaint` follow.
    ...(opacity < 1 ? { 'heatmap-opacity': effectiveOpacity(opacity) } : {}),
  })
}

function fillPaint(renderer: ClassicRenderer, opacity: number): FillLayerSpecification['paint'] {
  return defined<NonNullable<FillLayerSpecification['paint']>>({
    'fill-color': dataDriven(renderer, asFill, (symbol) => symbol.fillColor),
    'fill-opacity': dataDriven(renderer, asFill, (symbol) =>
      effectiveOpacity(symbol.fillOpacity * opacity),
    ),
    // MapLibre's fill layer draws a hairline outline and nothing else -- `outlineWidth`
    // has no counterpart and is not honoured. A wider outline would need a second line
    // layer per catalog layer, which is out of scope here.
    'fill-outline-color': dataDriven(renderer, asFill, (symbol) => symbol.outlineColor),
  })
}

function linePaint(renderer: ClassicRenderer, opacity: number): LineLayerSpecification['paint'] {
  // `line-dasharray` is not data-driven in MapLibre, so one pattern has to stand for the
  // whole layer: the single symbol, or the fallback of a classified renderer.
  const dashArray = asLine(representativeSymbol(renderer)).dashArray
  return defined<NonNullable<LineLayerSpecification['paint']>>({
    'line-color': dataDriven(renderer, asLine, (symbol) => symbol.color),
    'line-width': dataDriven(renderer, asLine, (symbol) => symbol.width),
    // Only written when it differs from MapLibre's own default, so an unmodified style
    // produces byte-for-byte the same paint object as no style at all.
    ...(opacity < 1 ? { 'line-opacity': effectiveOpacity(opacity) } : {}),
    ...(dashArray && dashArray.length > 0 ? { 'line-dasharray': dashArray } : {}),
  })
}

function circlePaint(renderer: ClassicRenderer, opacity: number): CircleLayerSpecification['paint'] {
  return defined<NonNullable<CircleLayerSpecification['paint']>>({
    // `size` is the radius in pixels, not the diameter -- that is what makes the default
    // symbol identical to the unstyled `circle-radius: 3`.
    'circle-radius': dataDriven(renderer, asMarker, (symbol) => symbol.size),
    'circle-color': dataDriven(renderer, asMarker, (symbol) => symbol.fillColor),
    'circle-stroke-width': dataDriven(renderer, asMarker, (symbol) => symbol.strokeWidth),
    'circle-stroke-color': dataDriven(renderer, asMarker, (symbol) => symbol.strokeColor),
    ...(opacity < 1
      ? {
          'circle-opacity': effectiveOpacity(opacity),
          'circle-stroke-opacity': effectiveOpacity(opacity),
        }
      : {}),
  })
}

function labelLayout(labels: LabelStyle, layer: VectorLayerSummary): SymbolLayerSpecification['layout'] {
  return defined<NonNullable<SymbolLayerSpecification['layout']>>({
    'text-field': ['get', labels.field],
    'text-font': LABEL_FONT,
    'text-size': numberOr(labels.size, DEFAULT_LABELS.size),
    'text-allow-overlap': labels.allowOverlap === true,
    // Street names read along the line, everything else sits on the centroid.
    'symbol-placement': layer.geometryType === 'MULTILINESTRING' ? 'line' : 'point',
  })
}

function labelPaint(labels: LabelStyle): SymbolLayerSpecification['paint'] {
  return defined<NonNullable<SymbolLayerSpecification['paint']>>({
    'text-color': colorOr(labels.color, DEFAULT_LABELS.color),
    'text-halo-color': colorOr(labels.haloColor, DEFAULT_LABELS.haloColor),
    'text-halo-width': numberOr(labels.haloWidth, DEFAULT_LABELS.haloWidth),
  })
}

/**
 * The font stack the label layer asks for. It has to exist in the glyph source
 * declared on the basemap style (`map/basemap.ts`) and as a folder under
 * `backend/.../resources/glyphs/` -- without matching PBFs MapLibre renders no
 * text at all and only says so on the error event.
 */
export const LABEL_FONT = ['Noto Sans Regular']

// -- symbol resolution ------------------------------------------------------------

/**
 * What actually arrives. The schema types every member of a symbol as present, but the
 * server omits null members, so a stored symbol may carry `kind` and nothing else.
 */
type Incoming<T> = { [K in keyof T]?: T[K] }

/**
 * A symbol always renders in the role its sublayer demands, not in the one its `kind`
 * names. A GEOMETRY layer draws the very same category symbol as polygon, line and
 * marker; only its colour carries across, everything else falls back to the default
 * for that role.
 *
 * Every member is filled in individually, including when `kind` already matches and the
 * symbol could be passed through. A spread would not do: `{...DEFAULT_FILL, ...symbol}`
 * lets a key that is present but `undefined` overwrite the default with nothing, and
 * `undefined` in a paint property makes MapLibre discard the whole layer -- the objects
 * vanish from the map, with one line on the console as the only sign.
 */
function asFill(symbol: LayerSymbol): FillSymbol {
  if (symbol.kind !== 'fill') return { ...DEFAULT_FILL, fillColor: primaryColorOf(symbol) }
  const fill = symbol as Incoming<FillSymbol>
  return {
    kind: 'fill',
    fillColor: colorOr(fill.fillColor, DEFAULT_FILL.fillColor),
    fillOpacity: numberOr(fill.fillOpacity, DEFAULT_FILL.fillOpacity),
    outlineColor: colorOr(fill.outlineColor, DEFAULT_FILL.outlineColor),
    outlineWidth: numberOr(fill.outlineWidth, DEFAULT_FILL.outlineWidth),
  }
}

function asLine(symbol: LayerSymbol): LineSymbol {
  if (symbol.kind !== 'line') return { ...DEFAULT_LINE, color: primaryColorOf(symbol) }
  const line = symbol as Incoming<LineSymbol>
  return {
    kind: 'line',
    color: colorOr(line.color, DEFAULT_LINE.color),
    width: numberOr(line.width, DEFAULT_LINE.width),
    dashArray: line.dashArray,
  }
}

function asMarker(symbol: LayerSymbol): MarkerSymbol {
  // The stroke stays the default light halo: it separates the marker from whatever is
  // beneath it, it is not part of the category's identity the way the fill is.
  if (symbol.kind !== 'marker') return { ...DEFAULT_MARKER, fillColor: primaryColorOf(symbol) }
  const marker = symbol as Incoming<MarkerSymbol>
  return {
    kind: 'marker',
    shape: marker.shape ?? DEFAULT_MARKER.shape,
    size: numberOr(marker.size, DEFAULT_MARKER.size),
    fillColor: colorOr(marker.fillColor, DEFAULT_MARKER.fillColor),
    strokeColor: colorOr(marker.strokeColor, DEFAULT_MARKER.strokeColor),
    strokeWidth: numberOr(marker.strokeWidth, DEFAULT_MARKER.strokeWidth),
  }
}

function representativeSymbol(renderer: ClassicRenderer): LayerSymbol {
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
  renderer: ClassicRenderer,
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

/**
 * What MapLibre accepts as a `match` branch label, stated positively.
 *
 * Everything else -- a missing value, an explicit null, a fraction, a boolean -- makes
 * the parser reject the *entire* expression, which costs the layer rather than the one
 * category. Stating the rule as "what is allowed" is what makes that safe: a value the
 * schema does not foresee is skipped instead of slipping through a list of exclusions.
 *
 * Skipping loses nothing. MapLibre answers a non-matching input with the default, so
 * those objects land on the fallback symbol, which is exactly where they belong.
 */
function isBranchLabel(value: unknown): value is string | number {
  return typeof value === 'string' || (typeof value === 'number' && Number.isSafeInteger(value))
}

function matchExpression<T extends string | number>(
  field: string,
  categories: { value: unknown; symbol: LayerSymbol }[],
  valueOf: (symbol: LayerSymbol) => T,
  fallback: T,
): PaintValue<T> {
  const branches: (string | number | T)[] = []
  const outputs: T[] = []
  const seen = new Set<string | number>()

  for (const category of categories) {
    const label = category.value
    if (!isBranchLabel(label)) continue
    // Duplicate labels are a parse error just the same, so the first definition wins.
    if (seen.has(label)) continue

    seen.add(label)
    const output = valueOf(category.symbol)
    branches.push(label, output)
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
  // A bound that is not a finite number cannot be a stop, and `step` rejects the whole
  // expression over one of them -- same cost as a bad `match` label.
  const usable = classes.filter((styleClass) => Number.isFinite(styleClass.min))
  if (usable.length === 0) return fallback

  const first = valueOf(usable[0].symbol)
  const outputs: T[] = [first]
  const stops: (number | T)[] = []
  let previous = usable[0].min

  for (const styleClass of usable.slice(1)) {
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
