/**
 * Resolves the background map and its opacity for the map that is actually on screen
 * (CONTRACT.md phase 18). A layer can override the project's basemap, its opacity,
 * both, or neither -- the two are decided independently, so a layer with its own map
 * but no own opacity still inherits the project's opacity, and the other way round.
 */

/**
 * The subset of a layer this module needs -- a plain object is enough for a test.
 * Optional, not just nullable, so `LayerSummary` (where both fields are optional --
 * an older server simply omits them) satisfies this without a cast.
 */
export interface BasemapOverride {
  basemap?: string | null
  basemapOpacity?: number | null
}

/** The subset of a project this module needs. A project always has both settings. */
export interface ProjectBasemapSettings {
  basemap: string | null | undefined
  basemapOpacity: number
}

export interface ResolvedBasemapSettings {
  /** Raw id to resolve through the catalog (`resolveBasemap`); not yet catalog-checked. */
  basemapId: string | null | undefined
  opacity: number
  /** True when `basemapId` came from the layer's own override, not from the project. */
  basemapFromLayer: boolean
  /** True when `opacity` came from the layer's own override, not from the project. */
  opacityFromLayer: boolean
}

/**
 * The active layer, or `null`/`undefined` while none is selected -- the normal state
 * right after opening a project (CONTRACT.md). Without an active layer the project's
 * own settings always apply.
 */
export function resolveBasemapSettings(
  layer: BasemapOverride | null | undefined,
  project: ProjectBasemapSettings,
): ResolvedBasemapSettings {
  const basemapFromLayer = layer?.basemap != null
  const opacityFromLayer = layer?.basemapOpacity != null
  return {
    basemapId: basemapFromLayer ? layer!.basemap : project.basemap,
    opacity: opacityFromLayer ? layer!.basemapOpacity! : project.basemapOpacity,
    basemapFromLayer,
    opacityFromLayer,
  }
}

/** Whether the layer overrides either the basemap or its opacity -- used to decide
 * which scope a picker opens on, and whether a layer's own action menu can offer a
 * "back to the project" reset in the first place. */
export function hasLayerBasemapOverride(layer: BasemapOverride | null | undefined): boolean {
  return layer?.basemap != null || layer?.basemapOpacity != null
}
