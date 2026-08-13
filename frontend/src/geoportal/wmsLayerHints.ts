/**
 * What the picker tells the user about a WMS layer before they choose it (plan Stufe 4,
 * "Zeig dabei ehrlich an, was der Dienst kann") -- pulled out of `MapImageSection` so the
 * formatting rules have a test of their own, independent of the component's DOM.
 */

/**
 * The scale window a service declares for one layer, as one line -- or `null` when the
 * service names neither bound, which is most of them (wms-api-vertrag.md: "minScale/
 * maxScale sind die Maßstabsnenner des Dienstes, oder null").
 *
 * `minScale` is the *smaller* scale denominator (contract wording: "Maßstabsnenner"),
 * i.e. the most zoomed-in bound a WMS `MinScaleDenominator` ever names -- so it reads as
 * "ab" (from here on, zooming out) and `maxScale` as "bis" (up to here, zooming in),
 * matching how a GetCapabilities document orders the two.
 */
export function formatWmsScaleLimits(minScale: number | null, maxScale: number | null): string | null {
  if (minScale === null && maxScale === null) return null
  const denominator = (value: number) => `1:${Math.round(value).toLocaleString('de-DE')}`
  if (minScale !== null && maxScale !== null) return `${denominator(minScale)} – ${denominator(maxScale)}`
  if (minScale !== null) return `ab ${denominator(minScale)}`
  return `bis ${denominator(maxScale as number)}`
}
