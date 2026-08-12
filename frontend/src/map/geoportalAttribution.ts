/**
 * Merges the licence notice of every visible Geoportal-sourced layer into `MapCanvas`'s
 * existing basemap attribution line (CONTRACT.md 11.7, plan 4.3: "Karte, unten rechts").
 *
 * Kept out of `MapCanvas.tsx` itself so the one rule that actually needs a test --
 * de-duplicating by attribution text among *visible* layers -- is exercised without a
 * DOM. `MapCanvas` only calls `combinedAttributionParts` and renders the result.
 */

import type { AttributionPart } from './basemap'
import type { LayerSummary } from '@/api/layers'

export interface GeoportalAttributionEntry {
  attribution: string
  licenseUrl: string
}

/**
 * One entry per distinct attribution text among the visible layers that carry a
 * Geoportal `source` -- an invisible layer credits nobody, and a licence line does not
 * name the same agency twice just because two of its layers are both on screen. Sorted
 * alphabetically for a stable, predictable order rather than the layer list's own
 * (creation-order-ish) sequence.
 */
export function distinctVisibleAttributions(
  layers: readonly LayerSummary[],
): GeoportalAttributionEntry[] {
  const byAttribution = new Map<string, GeoportalAttributionEntry>()
  for (const layer of layers) {
    if (!layer.visible || !layer.source) continue
    if (!byAttribution.has(layer.source.attribution)) {
      byAttribution.set(layer.source.attribution, {
        attribution: layer.source.attribution,
        licenseUrl: layer.source.licenseUrl,
      })
    }
  }
  return [...byAttribution.values()].sort((a, b) => a.attribution.localeCompare(b.attribution, 'de-DE'))
}

/** The short form the licence itself asks to be named by (CONTRACT.md 11.1's wording, plan 4.2). */
const LICENSE_SHORT_NAME = 'dl-de/by-2-0'

/**
 * Appends one run per Geoportal attribution to the basemap's own parts, each with its
 * licence linked (plan 4.3: "dazu der Link auf dl-de/by-2-0"). A separator only appears
 * where two runs actually meet, so an empty basemap attribution does not leave a stray
 * " · " in front of the first Geoportal entry.
 */
export function combinedAttributionParts(
  basemapAttribution: readonly AttributionPart[],
  entries: readonly GeoportalAttributionEntry[],
): AttributionPart[] {
  const parts: AttributionPart[] = [...basemapAttribution]
  for (const entry of entries) {
    if (parts.length > 0) parts.push({ text: ' · ' })
    parts.push(
      { text: entry.attribution },
      { text: ' (' },
      { text: LICENSE_SHORT_NAME, href: entry.licenseUrl },
      { text: ')' },
    )
  }
  return parts
}
