import type { GeometryType, LayerField } from '@/api/layers'
import { defaultSymbolFor, withPrimaryColor } from './defaults'
import { isNumericField } from './fields'
import type { LayerStyle, Renderer, RendererType } from './types'

/** Neutral grey for "everything the classification does not cover". */
export const FALLBACK_COLOR = '#a3a3a3'

/**
 * Switching the renderer keeps the symbol the user has already set up and only adds
 * what the new type needs. The classification itself is not carried over: `field: ''`
 * is the signal the editors use to ask for one, and `isPersistable` holds that state
 * back from the server, which would answer an unknown field with a 400.
 */
export function convertRenderer(
  style: LayerStyle,
  type: RendererType,
  geometryType: GeometryType,
  fields: LayerField[],
): Renderer {
  const base = style.renderer.type === 'single' ? style.renderer.symbol : style.renderer.fallbackSymbol
  if (type === 'single') return { type: 'single', symbol: base }

  const fallbackSymbol = withPrimaryColor(defaultSymbolFor(geometryType), FALLBACK_COLOR)
  // Read off the discriminator, not off the member being present: the server omits
  // null members, so absence says nothing about which renderer this is.
  const field = style.renderer.type === 'single' ? '' : style.renderer.field

  if (type === 'categorized') {
    return { type: 'categorized', field, categories: [], fallbackSymbol }
  }
  // A graduated renderer over a text column is a guaranteed 400 from `/classify`, so a
  // field carried over from the categorized renderer only survives if it is numeric.
  // Matched on the column name, which is what a style's `field` always holds.
  const numeric = fields.some((candidate) => candidate.columnName === field && isNumericField(candidate))
  return { type: 'graduated', field: numeric ? field : '', classes: [], fallbackSymbol }
}
