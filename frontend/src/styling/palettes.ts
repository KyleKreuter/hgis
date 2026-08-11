import { CATEGORY_PALETTE, COLOR_RAMPS, sampleRamp } from './defaults'

/** Distinct hues, as opposed to the ordered ramps -- the right default for categories. */
export const DEFAULT_CATEGORY_PALETTE = 'categorical'

/** An ordered ramp is what a graduated renderer needs; a hue set would hide the order. */
export const DEFAULT_RAMP = COLOR_RAMPS[0].id

/**
 * `count` colours from the named palette. The categorical one repeats once it runs out
 * -- past eight categories the colours stop telling anything apart anyway, and the
 * alternative would be to invent hues nobody chose.
 */
export function paletteColors(paletteId: string, count: number): string[] {
  if (paletteId === DEFAULT_CATEGORY_PALETTE) {
    return Array.from({ length: count }, (_, index) => CATEGORY_PALETTE[index % CATEGORY_PALETTE.length])
  }
  const ramp = COLOR_RAMPS.find((candidate) => candidate.id === paletteId) ?? COLOR_RAMPS[0]
  return sampleRamp(ramp, count)
}
