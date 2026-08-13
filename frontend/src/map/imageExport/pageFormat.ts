/**
 * Page formats and resolutions for the map image export (CONTRACT.md 13.2).
 *
 * The whole point of this module is the split between two pixel counts that are easy to
 * confuse:
 *
 * - `widthPx`/`heightPx` -- what the written file actually measures. A4 landscape at
 *   300 dpi is 3508 x 2480 px.
 * - `cssWidth`/`cssHeight` -- how big the hidden map's box is in CSS pixels, i.e. how
 *   much map the image covers. A CSS pixel is 1/96 inch by definition, so this is the
 *   same page at the browser's own reference resolution.
 *
 * `pixelRatio` is what turns the second into the first. Handing it to the export map is
 * what keeps labels and line widths at their physical size: MapLibre lays the map out in
 * CSS pixels and draws it at `pixelRatio` device pixels per CSS pixel, exactly as it does
 * for a retina screen. Rendering a 3508 px wide map at `pixelRatio` 1 instead would give
 * the same file size with every label a third of its proper height -- unreadable in print,
 * which is the only place a 300 dpi image is going.
 */

/** A CSS pixel is 1/96 inch. The reference every `pixelRatio` here is measured against. */
export const CSS_DPI = 96

const MM_PER_INCH = 25.4

export type PageFormatId = 'a4' | 'a3' | 'screen'
export type Orientation = 'portrait' | 'landscape'

/** Millimetres of the ISO A series, short edge first. */
const PAPER_MM: Record<'a4' | 'a3', { short: number; long: number }> = {
  a4: { short: 210, long: 297 },
  a3: { short: 297, long: 420 },
}

export interface PageChoice {
  /** Value of the picker entry; format and orientation in one, because the user picks one thing. */
  id: string
  label: string
  format: PageFormatId
  orientation: Orientation
}

/**
 * The picker's entries. "Wie am Bildschirm" carries an orientation too, so the type
 * stays uniform -- it is never read, since that format takes its box from the map panel.
 */
export const PAGE_CHOICES: readonly PageChoice[] = [
  { id: 'a4-landscape', label: 'A4 quer', format: 'a4', orientation: 'landscape' },
  { id: 'a4-portrait', label: 'A4 hoch', format: 'a4', orientation: 'portrait' },
  { id: 'a3-landscape', label: 'A3 quer', format: 'a3', orientation: 'landscape' },
  { id: 'a3-portrait', label: 'A3 hoch', format: 'a3', orientation: 'portrait' },
  { id: 'screen', label: 'Wie am Bildschirm', format: 'screen', orientation: 'landscape' },
]

export const DEFAULT_PAGE_CHOICE_ID = 'a4-landscape'

export interface ResolutionChoice {
  dpi: number
  label: string
  hint: string
}

export const RESOLUTIONS: readonly ResolutionChoice[] = [
  { dpi: 96, label: '96 dpi', hint: 'Für den Bildschirm' },
  { dpi: 150, label: '150 dpi', hint: 'Für einfache Ausdrucke' },
  { dpi: 300, label: '300 dpi', hint: 'Für den Druck' },
]

export const DEFAULT_DPI = 150

export function findPageChoice(id: string): PageChoice {
  return PAGE_CHOICES.find((choice) => choice.id === id) ?? PAGE_CHOICES[0]
}

export interface ImageSize {
  /** Pixels in the file. */
  widthPx: number
  heightPx: number
  /** The hidden map's box in CSS pixels -- always `widthPx / pixelRatio`, fractions included. */
  cssWidth: number
  cssHeight: number
  pixelRatio: number
}

export interface ScreenSize {
  width: number
  height: number
}

/**
 * Pixel dimensions and pixel ratio for one page choice at one resolution.
 *
 * `cssWidth` is derived back from the rounded `widthPx` rather than computed from the
 * millimetres directly. Both are within half a pixel of each other, but only this way
 * does `cssWidth * pixelRatio` land exactly on the file's own width -- and that identity
 * is what lets the caller check afterwards whether the browser really gave it the buffer
 * it asked for, instead of a silently smaller one.
 *
 * @param screen the visible map panel in CSS pixels; used by "wie am Bildschirm" only.
 */
export function computeImageSize(
  choice: PageChoice,
  dpi: number,
  screen: ScreenSize,
): ImageSize {
  const pixelRatio = dpi / CSS_DPI

  if (choice.format === 'screen') {
    // At least one pixel each way: a map panel that is still collapsed reports zero, and
    // a zero-sized canvas throws rather than producing an empty image.
    const widthPx = Math.max(1, Math.round(screen.width * pixelRatio))
    const heightPx = Math.max(1, Math.round(screen.height * pixelRatio))
    return {
      widthPx,
      heightPx,
      cssWidth: widthPx / pixelRatio,
      cssHeight: heightPx / pixelRatio,
      pixelRatio,
    }
  }

  const paper = PAPER_MM[choice.format]
  const widthMm = choice.orientation === 'landscape' ? paper.long : paper.short
  const heightMm = choice.orientation === 'landscape' ? paper.short : paper.long
  const widthPx = Math.round((widthMm / MM_PER_INCH) * dpi)
  const heightPx = Math.round((heightMm / MM_PER_INCH) * dpi)

  return {
    widthPx,
    heightPx,
    cssWidth: widthPx / pixelRatio,
    cssHeight: heightPx / pixelRatio,
    pixelRatio,
  }
}

/** "3508 × 2480 Pixel" -- for the hint under the picker and for the refusal message. */
export function describeImageSize(size: Pick<ImageSize, 'widthPx' | 'heightPx'>): string {
  return `${size.widthPx} × ${size.heightPx} Pixel`
}
