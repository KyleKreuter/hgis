import type { QueryClient } from '@tanstack/react-query'
import {
  layerClassifyQuery,
  layerValuesQuery,
  type ClassifyMethod,
  type ClassifyResult,
  type FieldValue,
  type FieldValuesResult,
  type GeometryType,
  type LayerField,
} from '@/api/layers'
import { defaultSymbolFor, primaryColorOf, withPrimaryColor } from './defaults'
import { formatCategoryValue, formatClassLabel } from './fields'
import { DEFAULT_CATEGORY_PALETTE, DEFAULT_RAMP, paletteColors } from './palettes'
import type { LayerSymbol, Renderer, StyleCategory, StyleClass } from './types'

/**
 * The field pickers carry the field's **id**, not either of its names.
 *
 * A picker holding a name is one careless edit away from writing the source name into
 * the style, and that failure is silent: the tile carries its properties under the
 * column name, so `['get', 'Straße']` finds nothing and every object drops to the
 * fallback symbol. An id cannot be confused with a name, and the one translation into
 * the column name lives here, under test.
 */
export function columnNameOfField(fields: LayerField[], fieldId: string): string {
  return fields.find((field) => field.id === fieldId)?.columnName ?? ''
}

/** The reverse, to preselect the picker from a stored style. */
export function fieldIdOfColumn(fields: LayerField[], columnName: string): string {
  return fields.find((field) => field.columnName === columnName)?.id ?? ''
}

/**
 * What the picker shows for a chosen field. Base UI renders the raw `value` in the
 * trigger, and since that value is a uuid, the field would read as one without this.
 */
export function sourceNameOfField(fields: LayerField[], fieldId: string): string {
  return fields.find((field) => field.id === fieldId)?.sourceName ?? ''
}

/**
 * Categories from the distinct values of a column.
 *
 * Objects without a value get no category of their own: MapLibre's `match` cannot carry
 * null as a branch label, so they land on the fallback symbol either way. `undefined` is
 * excluded next to it because the two say the same thing, not because the API is
 * expected to send it.
 */
export function buildCategories(
  values: FieldValue[],
  geometryType: GeometryType,
  palette: string,
): StyleCategory[] {
  const usable = values.filter((entry) => entry.value !== null && entry.value !== undefined)
  const colors = paletteColors(palette, usable.length)
  return usable.map((entry, index) => ({
    value: entry.value,
    label: formatCategoryValue(entry.value),
    symbol: withPrimaryColor(defaultSymbolFor(geometryType), colors[index]),
  }))
}

/**
 * Classes from the breaks `/classify` returned.
 *
 * `breaks` holds the lower bound of every class plus the maximum -- usually n+1 values,
 * but fewer when the column has fewer distinct values than classes were asked for. The
 * server drops repeated bounds because `step` rejects stops that do not ascend, so the
 * class count comes from the answer and never from what was requested.
 */
export function buildClasses(breaks: number[], geometryType: GeometryType, ramp: string): StyleClass[] {
  const count = Math.max(0, breaks.length - 1)
  const colors = paletteColors(ramp, count)
  return Array.from({ length: count }, (_, index) => ({
    min: breaks[index],
    max: breaks[index + 1],
    label: formatClassLabel(breaks[index], breaks[index + 1]),
    symbol: withPrimaryColor(defaultSymbolFor(geometryType), colors[index]),
  }))
}

/**
 * The symbol whose non-colour properties a classified renderer's "shared" symbol editor
 * shows -- the first entry's, since every entry `buildClasses`/`buildCategories` produces
 * carries exactly the same shape and differs only in colour.
 *
 * Falls back to `fallbackSymbol`, not to the layer's plain default, and that is what
 * makes a picked size survive a field change: `selectField` empties the class/category
 * list before the rebuild runs, but leaves `fallbackSymbol` alone, and `setSharedSymbol`
 * (see `GraduatedEditor`/`CategorizedEditor`) keeps that fallback's shape in step with
 * the classes' shape whenever the user edits it. So by the time the list is rebuilt from
 * scratch, the fallback is still carrying whatever shape the classes had before.
 */
export function sharedSymbolOf(entries: { symbol: LayerSymbol }[], fallbackSymbol: LayerSymbol): LayerSymbol {
  return entries[0]?.symbol ?? fallbackSymbol
}

/**
 * `template`'s properties, except colour -- colour is kept from `symbol` itself. The
 * single-symbol building block behind `withSharedSymbol`, used directly wherever a
 * symbol sits outside the classified list but still needs the exact same treatment: a
 * renderer's `fallbackSymbol` is exactly such a case (see `setSharedSymbol` in both
 * editors, which applies this to the fallback right alongside `withSharedSymbol` on the
 * list, so the two never drift apart).
 */
export function withSharedSymbolShape(symbol: LayerSymbol, template: LayerSymbol): LayerSymbol {
  return withPrimaryColor(template, primaryColorOf(symbol))
}

/**
 * Carries one symbol's non-colour properties -- size, width, stroke width, dash pattern,
 * whichever `kind` provides -- onto every entry of a classified list, leaving each
 * entry's own colour untouched.
 *
 * The one function both directions need: reapplying a size the user has set across a
 * rebuild that would otherwise reset every symbol back to `defaultSymbolFor` (as
 * `requestGraduatedClasses`/`requestCategorizedCategories` do whenever method, class
 * count, ramp or field change), and applying a freshly picked size to every class at
 * once when the user edits the shared symbol directly.
 */
export function withSharedSymbol<T extends { symbol: LayerSymbol }>(entries: T[], template: LayerSymbol): T[] {
  return entries.map((entry) => ({ ...entry, symbol: withSharedSymbolShape(entry.symbol, template) }))
}

/**
 * Method, class count and ramp to open a graduated renderer's panel with -- what
 * actually produced the stored `classes` if the style carries that (schema section A),
 * today's ordinary defaults otherwise, for a style saved before these fields existed.
 *
 * `GraduatedEditor` reads this exactly once, as its `useState` initial value, and never
 * again: nothing re-derives these controls from `renderer` after that, which is what
 * keeps opening the panel from looking like the user changing something (CONTRACT.md,
 * package B1). Exported for its own test, independent of the component around it.
 */
export function initialGraduatedControls(
  renderer: Pick<Extract<Renderer, { type: 'graduated' }>, 'method' | 'classCount' | 'ramp'>,
  classes: StyleClass[],
): { method: ClassifyMethod; classCount: number; ramp: string } {
  return {
    method: renderer.method ?? 'quantile',
    classCount: renderer.classCount ?? Math.max(2, classes.length || 5),
    ramp: renderer.ramp ?? DEFAULT_RAMP,
  }
}

/** The categorized counterpart to {@link initialGraduatedControls}: just the palette. */
export function initialCategorizedPalette(
  renderer: Pick<Extract<Renderer, { type: 'categorized' }>, 'palette'>,
): string {
  return renderer.palette ?? DEFAULT_CATEGORY_PALETTE
}

export interface GraduatedClassification {
  classes: StyleClass[]
  result: ClassifyResult
}

/**
 * Turns "field, method, classCount, ramp" into stored classes -- the one place
 * `/classify` is asked for a graduated renderer's classes. `queryClient.fetchQuery`
 * keeps the same 5-minute cache `layerClassifyQuery` already carries, so revisiting a
 * combination that was just requested does not cost another round trip.
 *
 * Called only from a user action: the Methode/Klassen/Farbverlauf controls in
 * `GraduatedEditor`, its own Feld picker, and `SymbologyPanel` when a renderer switch
 * carries a field over with nothing classified yet (`convertRenderer`). Never from an
 * effect that watches state -- that is exactly what turned "open the panel" into "lose
 * the saved classes" (CONTRACT.md, package B1).
 */
export async function requestGraduatedClasses(
  queryClient: QueryClient,
  layerId: string,
  geometryType: GeometryType,
  field: string,
  method: ClassifyMethod,
  classCount: number,
  ramp: string,
  existingClasses: StyleClass[],
  fallbackSymbol: LayerSymbol,
): Promise<GraduatedClassification> {
  const result = await queryClient.fetchQuery(layerClassifyQuery(layerId, field, method, classCount))
  const fresh = buildClasses(result.breaks, geometryType, ramp)
  // Carries the existing size/width across, same as `GraduatedEditor`'s old effect did
  // -- see `sharedSymbolOf` for why an empty `existingClasses` (a field that just
  // changed) falls back to `fallbackSymbol` instead.
  const shared = sharedSymbolOf(existingClasses, fallbackSymbol)
  return { classes: withSharedSymbol(fresh, shared), result }
}

/**
 * `min`/`max` for one numeric field -- what a heatmap renderer normalises
 * `heatmap-weight` against (`styleToMapLibre`), and what its legend labels its two
 * ends with (`HeatmapEditor`). A plain projection of `ClassifyResult`, which already
 * carries both alongside `breaks`.
 */
export type FieldRange = Pick<ClassifyResult, 'min' | 'max'>

// The `/classify` request every field-range lookup shares. `breaks` is never read here
// -- only `min`/`max` are -- so any valid (method, classes) pair would do; fixing one
// keeps every caller (the panel's own legend, `MapLayerSync`'s weight normalisation) on
// the same cache entry (`layerClassifyQuery`'s 5-minute `staleTime`) instead of paying
// for the same column scan twice.
const RANGE_METHOD: ClassifyMethod = 'quantile'
const RANGE_CLASS_COUNT = 2

/** The field-range counterpart to `layerClassifyQuery`/`layerValuesQuery` above. */
export function heatmapFieldRangeQuery(layerId: string, field: string) {
  return layerClassifyQuery(layerId, field, RANGE_METHOD, RANGE_CLASS_COUNT)
}

export interface CategorizedClassification {
  categories: StyleCategory[]
  result: FieldValuesResult
}

/** The categorized counterpart to {@link requestGraduatedClasses}, same rules. */
export async function requestCategorizedCategories(
  queryClient: QueryClient,
  layerId: string,
  geometryType: GeometryType,
  field: string,
  palette: string,
  existingCategories: StyleCategory[],
  fallbackSymbol: LayerSymbol,
): Promise<CategorizedClassification> {
  const result = await queryClient.fetchQuery(layerValuesQuery(layerId, field))
  const fresh = buildCategories(result.values, geometryType, palette)
  const shared = sharedSymbolOf(existingCategories, fallbackSymbol)
  return { categories: withSharedSymbol(fresh, shared), result }
}
