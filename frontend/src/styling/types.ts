/**
 * The stored style schema (plan section C, `layer.style` jsonb).
 *
 * Deliberately not the MapLibre style spec: that spec is renderer-specific and would
 * make a later export to QGIS or SLD impossible. `styleToMapLibre` is the one place
 * that knows about MapLibre; everything else in the app works against these types.
 */

import type { ClassifyMethod } from '@/api/layers'

export type RendererType = 'single' | 'categorized' | 'graduated'

/**
 * `shape` is fixed to `circle` in the MVP -- MapLibre cannot draw squares or triangles
 * without sprite images. The field exists so the schema stays stable; anything else is
 * treated as a circle rather than rejected.
 */
export interface MarkerSymbol {
  kind: 'marker'
  shape: string
  size: number
  fillColor: string
  strokeColor: string
  strokeWidth: number
}

export interface LineSymbol {
  kind: 'line'
  color: string
  width: number
  dashArray?: number[] | null
}

export interface FillSymbol {
  kind: 'fill'
  fillColor: string
  fillOpacity: number
  outlineColor: string
  outlineWidth: number
}

export type LayerSymbol = MarkerSymbol | LineSymbol | FillSymbol

/** The value as it appears in the data; `null` stands for objects without a value. */
export type CategoryValue = string | number | null

export interface StyleCategory {
  value: CategoryValue
  label: string
  symbol: LayerSymbol
}

/** Half-open on the upper end, matching how `/classify` reports its breaks. */
export interface StyleClass {
  min: number
  max: number
  label: string
  symbol: LayerSymbol
}

export type Renderer =
  | { type: 'single'; symbol: LayerSymbol }
  | {
      type: 'categorized'
      field: string
      categories: StyleCategory[]
      fallbackSymbol: LayerSymbol
      /**
       * Which named palette produced `categories`' colours -- a display name the
       * client owns, not a value the server interprets. Optional: absent on a style
       * saved before this field existed, and on one whose categories were coloured by
       * hand, one at a time, rather than through a palette. Reread when the panel
       * opens so a re-classification (a field change, "Farben neu verteilen") starts
       * from the palette that was actually used, not always the default.
       */
      palette?: string
    }
  | {
      type: 'graduated'
      field: string
      classes: StyleClass[]
      fallbackSymbol: LayerSymbol
      /** Which of `/classify`'s methods computed `classes`' bounds. */
      method?: ClassifyMethod
      /** How many classes were requested -- `classes` itself may hold fewer, see `buildClasses`. */
      classCount?: number
      /** Which named ramp produced `classes`' colours, the graduated counterpart to `palette` above. */
      ramp?: string
    }

export interface LabelStyle {
  enabled: boolean
  field: string
  size: number
  color: string
  haloColor: string
  haloWidth: number
  minZoom: number
  allowOverlap: boolean
}

export interface LayerStyle {
  version: 1
  renderer: Renderer
  labels?: LabelStyle | null
  /** 0..1, multiplied onto whatever opacity the symbol itself carries. */
  opacity: number
  minZoom?: number
  maxZoom?: number
}

/**
 * Which of the three MapLibre layer types a symbol has to be rendered as.
 *
 * Not the same thing as `LayerSymbol['kind']`: a GEOMETRY layer renders one and the
 * same category symbol in all three roles, so the role comes from the sublayer, and
 * the symbol only contributes its colours (see `resolveSymbol`).
 */
export type SymbolRole = 'point' | 'line' | 'polygon'
