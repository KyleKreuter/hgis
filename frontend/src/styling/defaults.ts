import type { GeometryType } from '@/api/layers'
import colorRamps from './colorRamps.json'
import defaultSymbolsJson from './defaultSymbols.json'
import type {
  FillSymbol,
  LabelStyle,
  LayerStyle,
  LayerSymbol,
  LineSymbol,
  MarkerSymbol,
  SymbolRole,
} from './types'

/**
 * The three default symbols reproduce the monochrome look a layer without a style has
 * (`layerSpecs.ts`), value for value. That is what lets the symbology panel start from
 * a concrete style object without the map changing appearance the moment it is opened
 * -- `defaults.test.ts` pins the two against each other.
 *
 * The values themselves live in `defaultSymbols.json`, not here, for the same reason
 * `COLOR_RAMPS` moved out below: the backend keeps its own copy
 * (`LayerStyleService.DEFAULT_MARKER`/`DEFAULT_LINE`/`DEFAULT_FILL`) to fall back to when
 * `cleanupAfterFieldRemoval` strips a renderer's field, and two hand-maintained copies
 * drift exactly the way the ramp catalogue did. `DefaultSymbolCatalogueTest` reads this
 * JSON and holds it against the backend's constants.
 *
 * `kind` is deliberately not a member of the JSON, unlike `id` in `colorRamps.json`. A
 * JSON string literal widens to plain `string` on import (measured: assigning
 * `{ kind: "marker", ... }` straight from a JSON import against a `{ kind: 'marker' }`
 * discriminant fails with TS2322, "string is not assignable to '\"marker\"'"), so it
 * could not satisfy `MarkerSymbol` et al. without a cast that would blunt the
 * discriminated union everywhere else it is used. The object's own key -- `marker`,
 * `line`, `fill` -- already tells the reader and `DefaultSymbolCatalogueTest` which
 * shape a block belongs to, so the literal is supplied once, here, in TypeScript.
 *
 * The type annotations below are load-bearing the same way `COLOR_RAMPS`' is: a JSON
 * import is structurally typed, and TypeScript still catches a *missing* member of
 * `MarkerSymbol`/`LineSymbol`/`FillSymbol` this way (measured: TS2741) -- but not an
 * *extra* one, because the excess-property check is a property of fresh object literals
 * and does not fire through a spread from an already-typed source (measured). A stray
 * key in the JSON would sit there unused and pass silently; `DefaultSymbolCatalogueTest`
 * checks the key set itself for exactly that reason.
 */
export const DEFAULT_MARKER: MarkerSymbol = { kind: 'marker', ...defaultSymbolsJson.marker }

export const DEFAULT_LINE: LineSymbol = { kind: 'line', ...defaultSymbolsJson.line }

export const DEFAULT_FILL: FillSymbol = { kind: 'fill', ...defaultSymbolsJson.fill }

export const DEFAULT_SYMBOLS: Record<SymbolRole, LayerSymbol> = {
  point: DEFAULT_MARKER,
  line: DEFAULT_LINE,
  polygon: DEFAULT_FILL,
}

/**
 * A GEOMETRY layer carries all three roles at once; `polygon` is picked because it is
 * the only one whose symbol has a distinct fill and outline, so nothing the user sets
 * is lost when the same symbol is rendered as a line or a marker too.
 */
export function roleFor(geometryType: GeometryType): SymbolRole {
  if (geometryType === 'MULTIPOINT') return 'point'
  if (geometryType === 'MULTILINESTRING') return 'line'
  return 'polygon'
}

export function defaultSymbolFor(geometryType: GeometryType): LayerSymbol {
  return DEFAULT_SYMBOLS[roleFor(geometryType)]
}

export function defaultStyleFor(geometryType: GeometryType): LayerStyle {
  return {
    version: 1,
    renderer: { type: 'single', symbol: defaultSymbolFor(geometryType) },
    opacity: 1,
  }
}

export function defaultLabels(field: string): LabelStyle {
  return {
    enabled: true,
    field,
    size: 12,
    color: '#262626',
    haloColor: '#ffffff',
    haloWidth: 1.5,
    // Labels at low zoom cover the whole map with text nobody can read; 12 is roughly
    // the level at which a city district fills the screen.
    minZoom: 12,
    allowOverlap: false,
  }
}

/** The label style every missing member falls back to. */
export const DEFAULT_LABELS = defaultLabels('')

/**
 * `radius`/`intensity` a fresh heatmap renderer starts from -- the same values
 * MapLibre's own `heatmap-radius`/`heatmap-intensity` default to, so switching to
 * "Heatmap" for the first time does not jump the moment a slider is first touched.
 */
export const DEFAULT_HEATMAP_RADIUS = 30
export const DEFAULT_HEATMAP_INTENSITY = 1

/**
 * The colour a symbol is identified by -- what a legend swatch and a colour picker show.
 *
 * Falls back rather than returning what it was given: the member is optional in the
 * stored JSON, and an `undefined` here would reach both a paint property (which costs
 * the entire layer, see `styleToMapLibre`) and the `value` of a colour input (which
 * turns it into an uncontrolled one).
 */
export function primaryColorOf(symbol: LayerSymbol): string {
  const color = symbol.kind === 'line' ? symbol.color : symbol.fillColor
  return colorOr(color, symbol.kind === 'marker' ? DEFAULT_MARKER.fillColor : DEFAULT_FILL.fillColor)
}

/**
 * A usable colour, or the fallback. The style schema types every colour as `string`,
 * but the value comes out of an API and may be missing or empty.
 */
export function colorOr(value: string | undefined | null, fallback: string): string {
  return typeof value === 'string' && value !== '' ? value : fallback
}

/** A usable number, or the fallback -- catches missing values and NaN alike. */
export function numberOr(value: number | undefined | null, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/**
 * The colour the layer tree tints its symbol preview with, or null to leave it neutral.
 *
 * Null for a classified renderer on purpose: a layer drawn in eight colours has no one
 * colour, and picking the first category's would claim something that is not true.
 */
export function previewColorOf(style: LayerStyle | null | undefined): string | null {
  if (!style || style.renderer.type !== 'single') return null
  return primaryColorOf(style.renderer.symbol)
}

/** Same symbol, different primary colour. Used when a palette is applied to categories. */
export function withPrimaryColor(symbol: LayerSymbol, color: string): LayerSymbol {
  return symbol.kind === 'line' ? { ...symbol, color } : { ...symbol, fillColor: color }
}

/**
 * Eight hues after Okabe/Ito, which stay distinguishable with the common forms of
 * colour blindness. Cycled when a layer has more categories than the palette has
 * entries -- at that point the categories are no longer telling anyone apart anyway.
 */
export const CATEGORY_PALETTE = [
  '#0072b2',
  '#d55e00',
  '#009e73',
  '#cc79a7',
  '#e69f00',
  '#56b4e9',
  '#a6761d',
  '#666666',
] as const

export interface ColorRamp {
  id: string
  label: string
  /** Anchor colours, sampled by linear interpolation. */
  stops: readonly string[]
}

/**
 * The catalogue lives in `colorRamps.json`, not in this file, for one reason: the backend
 * keeps the same list (`LayerStyleService.COLOR_RAMPS`) and rejects a ramp outside it. Two
 * hand-maintained copies of one list drift -- that is exactly how `"inferno"`, the value
 * this project's own contract, README and docstrings all named, came to paint a *blue*
 * heatmap. A test reads the JSON below and holds the server's own catalogue against it;
 * parsing JSON needs no guesswork, whereas reading ids out of TypeScript source with a
 * regular expression fails silently the moment a formatter touches the file.
 *
 * The annotation is load-bearing: a JSON import is structurally typed but never `readonly`,
 * and `satisfies` alone would *not* restore that -- it checks compatibility without
 * changing the variable's type, leaving `stops.push(...)` compiling happily.
 *
 * `inferno`/`viridis` carry five stops where every other ramp uses three. That is
 * deliberate: both curve, inferno sharply so -- it swings through violet and a distinct
 * orange "ember" band between its black start and yellow end -- and a 3-point linear mix
 * cuts that corner completely. Measured against `matplotlib.colormaps` directly, the worst
 * single-channel deviation runs to 125 of 255 at three stops, versus 35 at five.
 */
export const COLOR_RAMPS: readonly ColorRamp[] = colorRamps

/**
 * The catalogue's ids alone, in the order `COLOR_RAMPS` lists them. What a caller outside
 * this module -- a contract test, or the backend's own catalogue (`LayerStyleService`,
 * team review) -- reads to stay in step with the ramps this file actually offers, instead
 * of a second, hand-copied list that can drift the moment one side gains a ramp the other
 * does not. `defaults.test.ts` pins this exact array, so an addition here that is not
 * also relayed to the backend fails loudly on this side first.
 */
export const COLOR_RAMP_IDS: readonly string[] = COLOR_RAMPS.map((ramp) => ramp.id)

/** `count` evenly spaced colours from a ramp, first and last being its end points. */
export function sampleRamp(ramp: ColorRamp, count: number): string[] {
  if (count <= 0) return []
  if (count === 1) return [ramp.stops[0]]
  return Array.from({ length: count }, (_, index) =>
    interpolateStops(ramp.stops, index / (count - 1)),
  )
}

function interpolateStops(stops: readonly string[], position: number): string {
  const scaled = position * (stops.length - 1)
  const lower = Math.min(Math.floor(scaled), stops.length - 2)
  return mixHex(stops[lower], stops[lower + 1], scaled - lower)
}

/**
 * Interpolated in plain sRGB. Perceptually uniform spaces would space the classes
 * better, but a ramp of five or seven steps between hand-picked anchors is close
 * enough that the extra machinery would not show on screen.
 */
function mixHex(from: string, to: string, amount: number): string {
  const a = parseHex(from)
  const b = parseHex(to)
  const channel = (index: number) => Math.round(a[index] + (b[index] - a[index]) * amount)
  return `#${[0, 1, 2].map((index) => channel(index).toString(16).padStart(2, '0')).join('')}`
}

/** Exported for `styleToMapLibre`'s `heatmap-color` -- density 0 needs a transparent
 *  version of the ramp's own first colour, not a second hand-picked one. */
export function parseHex(color: string): [number, number, number] {
  const value = Number.parseInt(color.slice(1), 16)
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff]
}

/** `#rrggbb` only -- the same shape the server accepts, so the UI cannot produce a 400. */
export function isHexColor(value: string): boolean {
  return /^#[0-9a-fA-F]{6}$/.test(value)
}
