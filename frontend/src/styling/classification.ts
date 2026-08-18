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
 * `/classify` is asked for a graduated renderer's classes.
 *
 * `staleTime: 0` overrides `layerClassifyQuery`'s ordinary 5-minute cache: this only ever
 * runs from a user action -- "Klassen neu berechnen", a changed method or class count --
 * and every one of those is a request for a fresh answer (team review, package 2). Without
 * it, an identical `(field, method, classCount)` combination requested again inside the
 * five-minute window silently served the cached result, even though the layer's data may
 * have moved since. Cheap to always ask again -- a `/classify` call answers in tens of
 * milliseconds even on a large layer (team measurement, package 2) -- so nothing about
 * caching this particular read was actually saving anything worth keeping.
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
  const result = await queryClient.fetchQuery({ ...layerClassifyQuery(layerId, field, method, classCount), staleTime: 0 })
  const fresh = buildClasses(result.breaks, geometryType, ramp)
  // Carries the existing size/width across, same as `GraduatedEditor`'s old effect did
  // -- see `sharedSymbolOf` for why an empty `existingClasses` (a field that just
  // changed) falls back to `fallbackSymbol` instead.
  const shared = sharedSymbolOf(existingClasses, fallbackSymbol)
  return { classes: withSharedSymbol(fresh, shared), result }
}

/**
 * `min`/`max` for one numeric field, guaranteed finite -- what a heatmap renderer
 * normalises `heatmap-weight` against (`styleToMapLibre`), and what its legend labels
 * its two ends with (`HeatmapEditor`).
 *
 * Deliberately not a projection of `ClassifyResult` (whose own `min`/`max` are
 * `number | null` -- an empty column has no range to report): this type exists so
 * everything downstream of `resolveRangeState` can read `.min`/`.max` as plain numbers
 * without re-checking nullability at every use. `resolveRangeState` is the one place
 * that conversion happens; nothing else constructs a `FieldRange`.
 */
export interface FieldRange {
  min: number
  max: number
}

/**
 * What `MapLayerSync` (`heatmapFieldRanges.ts`) and `HeatmapEditor`'s own legend know
 * about a heatmap's weight field range at render time -- and the reason `styleToMapLibre`
 * needs any state logic here at all where `categorized`/`graduated` need none: their
 * classes are computed once and stored in the style itself, self-sufficient from then
 * on, while a heatmap's `field` is only ever a column name -- the range has to be
 * re-fetched live on every map render, so "the fetch has not settled" and "the fetch
 * will never settle" are both real states something has to render for.
 *
 * `undefined` while the request has not settled yet, or for a renderer that never needed
 * one (no field -- density mode).
 *
 * Two distinct flavours of "will never settle", not one, because they call for different
 * advice (`heatmapFieldRanges.ts`'s `useHeatmapRangeErrorToasts`): `'error'` is a request
 * that failed outright -- a network problem, worth a retry, "laden Sie die Seite neu" is
 * honest advice. `'invalid'` is a request that *succeeded* with a `ClassifyResult` that is
 * not a real range at all -- `min`/`max` came back `null` (a layer with no objects, or
 * every object missing the field), non-finite, or reversed (`min > max`, which the real
 * `/classify` endpoint cannot produce -- both bounds come from the same SQL aggregation
 * over the same column, and SQL guarantees `min <= max` -- but costs one line to rule out
 * for good rather than trust). Reloading a page changes nothing about `'invalid'`: the
 * exact same query, same key, would answer the exact same way -- telling a user to reload
 * for a data problem is advice that cannot help. Both used to collapse into a single
 * `'error'` and a single unconditional "laden Sie die Seite neu", which is precisely
 * backwards for the `'invalid'` half (team review, package 2).
 *
 * `range.max > range.min` alone would read `null > null` (coerced to `0 > 0`) as plain
 * `false` and land silently in the same bucket as "loading" -- exactly the bug that used
 * to leave a heatmap's own legend claiming a range of "0 bis 0" no data ever produced.
 * A resolved `FieldRange` otherwise.
 *
 * `undefined` and `'error'`/`'invalid'` are deliberately not the same thing to
 * `styleToMapLibre`: the first is `heatmapWeight`'s existing "count everyone equally, a
 * transient state that self-heals" fallback; the other two additionally swap
 * `heatmap-color` for a diagnostic pattern no ramp in `COLOR_RAMPS` can produce, because a
 * heatmap that will never learn its field's range would otherwise render exactly like
 * density mode forever -- a result that looks finished and is not. `styleToMapLibre`
 * itself does not need to tell `'error'` and `'invalid'` apart -- both get the same
 * diagnostic treatment -- only the toast's wording does.
 */
export type FieldRangeState = FieldRange | 'error' | 'invalid' | undefined

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

/** A one-click `weightMin`/`weightMax` starting point (`types.ts`, renderer contract
 *  package 2), e.g. `{ min: 12, max: 187 }`. */
export interface HeatmapWeightSuggestion {
  min: number
  max: number
}

/**
 * Classes requested for the weight-bound suggestion's quantile breaks -- the server's own
 * ceiling (`ClassificationService.MAX_CLASSES = 12`, backend), the finest split `/classify`
 * can compute without any new server capability. Twelve classes give breaks at 0, 1/12,
 * 2/12, ..., 11/12, 1 -- i.e. roughly the 0th, 8.3rd, 16.7th, ..., 91.7th and 100th
 * percentile, and 11/12 ≈ 91.7 % is exactly what `weightSuggestionFromBreaks` below hands
 * back as the "upper" suggestion.
 *
 * 11/12 is the reachable optimum, not a rough stand-in -- checked exhaustively, not just
 * argued: for every `classes` from 2 to 12, `k/classes` was compared against 0.95, and no
 * combination lands closer than 11/12 (team review, package 2, in reply to "zieh auf p95
 * nach"). A true 95th percentile needs a 20-way split (0.95 = 19/20, i.e. `classes = 20`),
 * which the server's own `classes <= 12` rejects with a 400 before this code ever runs.
 *
 * Interpolating between 11/12 and the true maximum (index `breaks.length - 1`, always
 * available) to approximate 0.95 more closely was considered and rejected: for a
 * `waermebedarf_unsaniert`-shaped field the true maximum *is* the outlier this whole
 * mechanism exists to stay away from (45 280 554 against a median of 188 843), so
 * interpolating toward it pulls the estimate back toward the very value being avoided,
 * worse the more skewed the field is -- exactly backwards for what a suggestion near the
 * top end is supposed to do.
 *
 * Raising `MAX_CLASSES` to reach 19/20 was considered and rejected too, deliberately not
 * here: that ceiling exists for legend readability (`ClassificationService`, "above twelve
 * no legend is readable any more"), a reason this internal, non-legend caller does not
 * share -- but weakening a clear limit for one caller's convenience turns it into one with
 * an exception, and the next exception then has this one to point to. Left as a
 * deliberate choice, not a gap: measured against the field this was built for
 * (`waermebedarf_unsaniert`, 46 233 real buildings), 11/12 already lifts the median's
 * weight from 0,0042 (today's plain maximum) to roughly 0,21 -- a factor of about fifty --
 * against 0,154 at a true, unreachable 95th percentile.
 *
 * That is not 11/12 beating a true 95th percentile -- it is a lower cutoff buying a higher
 * median weight at the cost of clamping more objects to the same colour: 11/12 clamps
 * about 8,3 % of this field's buildings (3 855 of 46 233, measured), a true 95th percentile
 * only 5 %. 11/12 sits closer to p90's trade-off (10 % clamped, median weight 0,242) than
 * to p95's. The distance from 11/12 to a true 95th percentile is fine-tuning next to the
 * distance 11/12 has already closed from today's plain maximum -- but it is a trade, not a
 * win already banked (team review, package 2 addendum: the first version of this
 * paragraph read as the opposite, comparing the two weights alone without ever naming
 * either one's clamped share).
 */
const WEIGHT_SUGGESTION_CLASSES = 12

/**
 * Turns one `/classify` quantile response's `breaks` into a `weightMin`/`weightMax`
 * suggestion -- the near-8th and near-92nd percentile (see `WEIGHT_SUGGESTION_CLASSES`
 * above for why not exactly 8th/92nd), one step in from either true end so an outlier at
 * the very top or bottom of the field never becomes the suggestion itself.
 *
 * `undefined` below four breaks: with three breaks or fewer (a field with very few
 * distinct values, after the server's own `strictlyAscending` dedup drops repeats) index 1
 * and index `length - 2` either coincide or invert -- at `length === 2` (`[min, max]`),
 * index 1 *is* `max` and index `length - 2` *is* `min`, which would silently swap the two
 * ends instead of producing a narrower one. Four breaks is the smallest count where index 1
 * and index `length - 2` are still two genuinely different, correctly ordered interior
 * points (`length === 4`: indices 1 and 2). Below that there is no meaningful interior
 * quantile to suggest -- the field's plain min/max (already what the automatic stretch
 * uses) is the honest answer, not a fabricated "percentile" that happens to equal one end.
 *
 * Pure and exported on its own so this rule is tested directly against `breaks` arrays of
 * every length that matters, without a network round trip standing in the way.
 */
export function weightSuggestionFromBreaks(breaks: number[]): HeatmapWeightSuggestion | undefined {
  if (breaks.length < 4) return undefined
  return { min: breaks[1], max: breaks[breaks.length - 2] }
}

/**
 * Fetches the quantile breaks `weightSuggestionFromBreaks` needs and turns them into a
 * suggestion -- the one place a user action (`HeatmapEditor`'s suggestion button) asks
 * `/classify` for this.
 *
 * `staleTime: 0` overrides `layerClassifyQuery`'s ordinary 5-minute cache on purpose (team
 * review, package 2 -- found alongside `requestGraduatedClasses`'s matching issue, same
 * fix applied there too): someone pressing a button that computes something has asked for
 * a fresh answer, not whatever answer happened to already sit in the cache from up to five
 * minutes ago -- and `WEIGHT_SUGGESTION_CLASSES = 12` can coincide with a `GraduatedEditor`
 * classification's own `classCount`, sharing the very cache entry a stale read here would
 * silently reuse. The cost of asking again is not worth avoiding to begin with -- a
 * `/classify` call answers in tens of milliseconds even on a large layer (team
 * measurement, package 2) -- so there is no efficiency this trades away, only a
 * correctness gap it closes.
 */
export async function requestHeatmapWeightSuggestion(
  queryClient: QueryClient,
  layerId: string,
  field: string,
): Promise<HeatmapWeightSuggestion | undefined> {
  const result = await queryClient.fetchQuery({
    ...layerClassifyQuery(layerId, field, 'quantile', WEIGHT_SUGGESTION_CLASSES),
    staleTime: 0,
  })
  return weightSuggestionFromBreaks(result.breaks)
}

/**
 * Whether a `weightMin`/`weightMax` draft is the one shape `HeatmapEditor` is allowed to
 * write into `renderer`: both present, and ascending. Mirrors the server's own rule
 * exactly (`LayerStyleService.requireWeightRange`, backend package 2 decision, both
 * "either both or neither" and "`weightMax` strictly greater than `weightMin`,
 * equality included as a rejection") -- catching a violation here, before the PATCH, is
 * what a user sees as an inline hint instead of a 400 from the server.
 *
 * The one place this check lives: `HeatmapEditor` derives its "commit to the renderer"
 * decision and its two validation hints ("both needed" / "wrong order") from the same
 * call, so they cannot silently drift apart into disagreeing about what counts as valid.
 * `undefined` covers both failure shapes at once (incomplete, or complete but not
 * ascending) -- the caller tells them apart itself where the wording differs (an empty
 * box reads differently from two full ones in the wrong order).
 */
export function resolveWeightBounds(
  min: number | undefined,
  max: number | undefined,
): HeatmapWeightSuggestion | undefined {
  if (min === undefined || max === undefined || !(max > min)) return undefined
  return { min, max }
}

/**
 * One query result, narrowed to the two members `resolveRangeState` reads --
 * structural on purpose so this stays independent of exactly which `useQueries`/
 * `useQuery` result type the installed TanStack Query version infers.
 */
export interface RangeQueryResult {
  isError: boolean
  data: Pick<ClassifyResult, 'min' | 'max'> | undefined
}

/**
 * Turns one `/classify` query's result into the four states `FieldRangeState`
 * distinguishes -- the one place a raw, nullable `ClassifyResult` becomes `'error'`,
 * `'invalid'`, or a `FieldRange` guaranteed finite and correctly ordered. Shared by
 * `MapLayerSync` (`heatmapFieldRanges.ts`, the map's own weight normalisation) and
 * `HeatmapEditor`'s legend, so both agree on what "the range is unavailable" means --
 * before this they did not: the legend read its own query's `data` directly and had no
 * notion of failure at all, which is how it ended up formatting a `null` `min`/`max` with
 * `Intl.NumberFormat` (silently `"0"`) into a plausible-looking but fabricated
 * "0 bis 0" (team review, package 2).
 *
 * A request that failed outright is `'error'`. Everything that *succeeded* with a
 * `ClassifyResult` that is not usable as a range is `'invalid'`: non-finite (or `null`)
 * `min`/`max`, and a reversed pair (`min > max`) -- the real `/classify` endpoint cannot
 * produce the latter (`min`/`max` come from one SQL aggregation over one column, and SQL
 * guarantees `min <= max`), but ruling it out here costs one comparison against trusting
 * an invariant this function has no way to verify. Without either check,
 * `range.max > range.min` alone would read `null > null` (coerced to `0 > 0`) or a
 * reversed pair as plain `false` and land silently in the same bucket as "still loading".
 */
export function resolveRangeState(result: RangeQueryResult | undefined): FieldRangeState {
  if (result?.isError) return 'error'
  const data = result?.data
  if (!data) return undefined
  // Narrowed out of `number | null` explicitly, in its own check: `Number.isFinite` is
  // not a type guard as far as TypeScript is concerned, so it does not narrow `data.min`/
  // `data.max` for the `min > max` comparison right after it -- this is what does.
  if (data.min === null || data.max === null) return 'invalid'
  if (!Number.isFinite(data.min) || !Number.isFinite(data.max) || data.min > data.max) return 'invalid'
  return { min: data.min, max: data.max }
}

/**
 * Whether a graduated renderer's outermost class is *guaranteed* to hold nothing from
 * the current data -- not a percentage, not a simulation of where quantile/equalInterval
 * would redraw the breaks, just the one comparison the class boundaries themselves
 * already answer.
 *
 * `classes[classes.length - 1].min` is exactly where the top class's own range starts --
 * `buildClasses` keeps every class's `max` equal to the next one's `min`, so this is the
 * same value as `classes[classes.length - 2].max`, under whichever name is at hand. A
 * live maximum below it cannot put anything inside that class's `[min, max)` range,
 * independent of `method` or how the other classes are shaped: this follows straight
 * from `stepExpression`'s own semantics (`styleToMapLibre.ts`), the same one
 * `GraduatedEditor`'s "data exceeds the stored bound" check reads.
 *
 * `classes.length < 2` (one class, or none) returns `false` rather than throwing --
 * there is no second-to-last boundary to compare against, and a single class is
 * trivially never "empty above itself" in the sense this function checks.
 */
export function upperClassIsEmpty(classes: Pick<StyleClass, 'min' | 'max'>[], liveMax: number): boolean {
  if (classes.length < 2) return false
  return liveMax < classes[classes.length - 1].min
}

/** The lower-bound counterpart to {@link upperClassIsEmpty} -- same reasoning, the other
 *  end: `classes[0].max` is where the bottom class's own range ends. */
export function lowerClassIsEmpty(classes: Pick<StyleClass, 'min' | 'max'>[], liveMin: number): boolean {
  if (classes.length < 2) return false
  return liveMin > classes[0].max
}

export interface CategorizedClassification {
  categories: StyleCategory[]
  result: FieldValuesResult
}

/** The categorized counterpart to {@link requestGraduatedClasses}, same rules --
 *  including `staleTime: 0` (team review, package 2), for the same reason: a user action
 *  asking to re-list a field's values has asked for a fresh list, not `layerValuesQuery`'s
 *  ordinary 5-minute cache. */
export async function requestCategorizedCategories(
  queryClient: QueryClient,
  layerId: string,
  geometryType: GeometryType,
  field: string,
  palette: string,
  existingCategories: StyleCategory[],
  fallbackSymbol: LayerSymbol,
): Promise<CategorizedClassification> {
  const result = await queryClient.fetchQuery({ ...layerValuesQuery(layerId, field), staleTime: 0 })
  const fresh = buildCategories(result.values, geometryType, palette)
  const shared = sharedSymbolOf(existingCategories, fallbackSymbol)
  return { categories: withSharedSymbol(fresh, shared), result }
}
