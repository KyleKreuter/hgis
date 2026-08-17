import { CATEGORY_PALETTE, COLOR_RAMPS, sampleRamp } from './defaults'

/** Distinct hues, as opposed to the ordered ramps -- the right default for categories. */
export const DEFAULT_CATEGORY_PALETTE = 'categorical'

/** An ordered ramp is what a graduated renderer needs; a hue set would hide the order. */
export const DEFAULT_RAMP = COLOR_RAMPS[0].id

/**
 * The ramp `paletteColors` draws from for a non-categorical `paletteId` -- falls back to
 * `COLOR_RAMPS[0]` for a name that is not in the catalogue. The one place that fallback is
 * decided, so `paletteColors` and `resolvePaletteId` below can never disagree about what
 * an unknown name actually paints.
 */
function resolveRamp(paletteId: string) {
  return COLOR_RAMPS.find((candidate) => candidate.id === paletteId) ?? COLOR_RAMPS[0]
}

/**
 * `count` colours from the named palette. The categorical one repeats once it runs out
 * -- past eight categories the colours stop telling anything apart anyway, and the
 * alternative would be to invent hues nobody chose.
 */
export function paletteColors(paletteId: string, count: number): string[] {
  if (paletteId === DEFAULT_CATEGORY_PALETTE) {
    return Array.from({ length: count }, (_, index) => CATEGORY_PALETTE[index % CATEGORY_PALETTE.length])
  }
  return sampleRamp(resolveRamp(paletteId), count)
}

/**
 * The id `paletteColors` actually painted with for `paletteId` -- itself, once it
 * resolves (the categorical set, or a name in `COLOR_RAMPS`), or `DEFAULT_RAMP`, the same
 * fallback `paletteColors` silently draws its colours from otherwise.
 *
 * Team review, package 3 addendum: unlike the heatmap's `ramp`, nothing here ever reads
 * `palette`/`ramp` to decide a colour on the map -- every category or class carries its
 * own colour in `symbol`, so an unresolved name can never make the map itself lie, and
 * `paletteLabel` (`labels.ts`) already shows an unresolved name raw rather than
 * translating it away, so the picker does not lie either. Only the *stored* renderer
 * could: the moment a control repaints every category/class from `DEFAULT_RAMP` while
 * leaving the old, unresolved name in `renderer.palette`/`.ramp`, the style claims a
 * palette the colours on screen no longer match. Every place that can turn a `palette`/
 * `ramp` string into fresh colours and write the result back -- `CategorizedEditor.tsx`'s
 * `recolor` and `request`, `GraduatedEditor.tsx`'s `request` -- writes this resolved id
 * back instead of the name it was given, so the stored state always names the palette
 * that was actually painted. `SymbologyPanel.tsx`'s `switchRenderer` needs none of this:
 * `convertRenderer` never carries a `palette`/`ramp` value over a renderer-type switch,
 * so `initialCategorizedPalette`/`initialGraduatedControls` only ever default a *missing*
 * value there, never revalidate one that is merely wrong.
 */
export function resolvePaletteId(paletteId: string): string {
  return paletteId === DEFAULT_CATEGORY_PALETTE ? paletteId : resolveRamp(paletteId).id
}
