import type { GeometryType } from '@/api/layers'
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
 */
export const DEFAULT_MARKER: MarkerSymbol = {
  kind: 'marker',
  shape: 'circle',
  size: 3,
  fillColor: '#404040',
  strokeColor: '#fafafa',
  strokeWidth: 1,
}

export const DEFAULT_LINE: LineSymbol = {
  kind: 'line',
  color: '#404040',
  width: 1.25,
}

export const DEFAULT_FILL: FillSymbol = {
  kind: 'fill',
  fillColor: '#404040',
  fillOpacity: 0.25,
  outlineColor: '#262626',
  outlineWidth: 1,
}

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

export const COLOR_RAMPS: readonly ColorRamp[] = [
  { id: 'blues', label: 'Blau', stops: ['#eff5fb', '#6baed6', '#08306b'] },
  { id: 'reds', label: 'Rot', stops: ['#fff2ec', '#f16913', '#7f2704'] },
  { id: 'greens', label: 'Grün', stops: ['#f1f8ee', '#74c476', '#00441b'] },
  { id: 'greys', label: 'Grau', stops: ['#f5f5f5', '#404040', '#0a0a0a'] },
  { id: 'diverging', label: 'Divergierend', stops: ['#2166ac', '#f7f7f7', '#b2182b'] },
]

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

function parseHex(color: string): [number, number, number] {
  const value = Number.parseInt(color.slice(1), 16)
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff]
}

/** `#rrggbb` only -- the same shape the server accepts, so the UI cannot produce a 400. */
export function isHexColor(value: string): boolean {
  return /^#[0-9a-fA-F]{6}$/.test(value)
}
