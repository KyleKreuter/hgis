import type { FieldValue, GeometryType, LayerField } from '@/api/layers'
import { defaultSymbolFor, primaryColorOf, withPrimaryColor } from './defaults'
import { formatCategoryValue, formatClassLabel } from './fields'
import { paletteColors } from './palettes'
import type { LayerSymbol, StyleCategory, StyleClass } from './types'

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
 * `GraduatedEditor`'s and `CategorizedEditor`'s effects do whenever method, class count,
 * ramp or field change), and applying a freshly picked size to every class at once when
 * the user edits the shared symbol directly.
 */
export function withSharedSymbol<T extends { symbol: LayerSymbol }>(entries: T[], template: LayerSymbol): T[] {
  return entries.map((entry) => ({ ...entry, symbol: withSharedSymbolShape(entry.symbol, template) }))
}
