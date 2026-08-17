import type { ClassifyMethod } from '@/api/layers'
import { COLOR_RAMPS } from './defaults'
import { DEFAULT_CATEGORY_PALETTE } from './palettes'
import type { RendererType } from './types'

/**
 * Every `<SelectValue>` needs one of these.
 *
 * Base UI renders the raw `value` in the trigger, not the text of the chosen item -- so
 * a select whose values are technical (`categorized`, a field's uuid) shows exactly that
 * to the user until a translation is passed in, the way `ImportDialog` does it. The
 * lists below are ordered as the menu shows them; the lookup is separate from the order.
 */
export function labelOf<T extends string>(options: readonly (readonly [T, string])[], value: string): string {
  return options.find(([candidate]) => candidate === value)?.[1] ?? value
}

export const RENDERER_LABELS: readonly (readonly [RendererType, string])[] = [
  ['single', 'Einzelsymbol'],
  ['categorized', 'Kategorisiert'],
  ['graduated', 'Abgestuft'],
  ['heatmap', 'Heatmap'],
]

export const METHOD_LABELS: readonly (readonly [ClassifyMethod, string])[] = [
  ['quantile', 'Quantile'],
  ['equalInterval', 'Gleiche Intervalle'],
  // Named as what it is: the server approximates Jenks with ntile, because exact Jenks
  // is quadratic and unusable on a large layer.
  ['naturalBreaks', 'Natürliche Unterbrechungen (genähert)'],
]

/** Lengths are multiples of the line width, which is how MapLibre reads a dash array. */
export const DASH_PATTERNS = {
  solid: null,
  dashed: [3, 2],
  dotted: [1, 2],
  dashdot: [4, 2, 1, 2],
} satisfies Record<string, number[] | null>

export type DashKey = keyof typeof DASH_PATTERNS

export const DASH_LABELS: readonly (readonly [DashKey, string])[] = [
  ['solid', 'Durchgezogen'],
  ['dashed', 'Gestrichelt'],
  ['dotted', 'Gepunktet'],
  ['dashdot', 'Strichpunkt'],
]

export function dashKeyOf(dashArray: number[] | null | undefined): DashKey {
  if (!dashArray || dashArray.length === 0) return 'solid'
  const match = (Object.keys(DASH_PATTERNS) as DashKey[]).find(
    (key) => JSON.stringify(DASH_PATTERNS[key]) === JSON.stringify(dashArray),
  )
  return match ?? 'dashed'
}

export function paletteLabel(paletteId: string): string {
  if (paletteId === DEFAULT_CATEGORY_PALETTE) return 'Kategorien'
  return COLOR_RAMPS.find((ramp) => ramp.id === paletteId)?.label ?? paletteId
}
