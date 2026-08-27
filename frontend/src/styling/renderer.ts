import type { GeometryType, LayerField } from '@/api/layers'
import { DEFAULT_FILL, DEFAULT_HEATMAP_INTENSITY, DEFAULT_HEATMAP_RADIUS, defaultSymbolFor, withPrimaryColor } from './defaults'
import { isNumericField } from './fields'
import { DEFAULT_RAMP } from './palettes'
import type { LayerStyle, LayerSymbol, Renderer, RendererType } from './types'

/** Neutral grey for "everything the classification does not cover". */
export const FALLBACK_COLOR = '#a3a3a3'

/**
 * The symbol a renderer switch carries forward. `heatmap` has none of its own -- it
 * draws no `LayerSymbol` at all -- so switching away from it starts from the layer's
 * plain default rather than from a colour that was never actually chosen.
 */
function symbolOf(renderer: Renderer, geometryType: GeometryType): LayerSymbol {
  if (renderer.type === 'single') return renderer.symbol
  if (renderer.type === 'heatmap') return defaultSymbolFor(geometryType)
  // A stored categorized/graduated renderer can still be missing fallbackSymbol -- an
  // older row, or a client that bypassed validation, from before the server started
  // requiring it (`LayerStyleService.validateRenderer`). Same fallback
  // `styleToMapLibre.ts`'s `dataDriven` uses for the identical gap, so switching such a
  // renderer to "single" degrades to the same grey instead of crashing the panel.
  return renderer.fallbackSymbol ?? DEFAULT_FILL
}

/**
 * The field a renderer switch carries forward, as the `''`/`null` sentinel each side
 * uses for "none" -- `categorized`/`graduated` read `''` as "not chosen yet" (see
 * `isPersistable`), `heatmap` reads `null` as a genuine, persisted "no field" (`types.ts`).
 * `single` and a field-less `heatmap` both have nothing to carry, hence the shared `''`.
 */
function fieldOf(renderer: Renderer): string {
  if (renderer.type === 'single') return ''
  if (renderer.type === 'heatmap') return renderer.field ?? ''
  return renderer.field
}

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
  if (type === 'single') return { type: 'single', symbol: symbolOf(style.renderer, geometryType) }

  const field = fieldOf(style.renderer)
  // A numeric-only field carries over into a numeric-only renderer; matched on the
  // column name, which is what a style's `field` always holds.
  const numeric = fields.some((candidate) => candidate.columnName === field && isNumericField(candidate))

  if (type === 'heatmap') {
    return {
      type: 'heatmap',
      field: numeric ? field : null,
      radius: DEFAULT_HEATMAP_RADIUS,
      intensity: DEFAULT_HEATMAP_INTENSITY,
      ramp: DEFAULT_RAMP,
    }
  }

  const fallbackSymbol = withPrimaryColor(defaultSymbolFor(geometryType), FALLBACK_COLOR)
  if (type === 'categorized') {
    return { type: 'categorized', field, categories: [], fallbackSymbol }
  }
  // A graduated renderer over a text column is a guaranteed 400 from `/classify`, so a
  // field carried over from the categorized renderer only survives if it is numeric.
  return { type: 'graduated', field: numeric ? field : '', classes: [], fallbackSymbol }
}
