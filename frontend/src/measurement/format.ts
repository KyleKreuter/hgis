/**
 * Measured values as text: a unit that suits the magnitude, and a precision that does
 * not pretend to more than was measured.
 */

const formatters = new Map<number, Intl.NumberFormat>()

function fixed(value: number, digits: number): string {
  let formatter = formatters.get(digits)
  if (!formatter) {
    formatter = new Intl.NumberFormat('de-DE', {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    })
    formatters.set(digits, formatter)
  }
  return formatter.format(value)
}

/**
 * Four significant digits, near enough: two decimals on a small number, none on a
 * large one. Below ten metres the centimetres still say something; at four digits
 * they are noise from a mouse click.
 */
function decimalsFor(value: number): number {
  if (value < 10) return 2
  if (value < 1000) return 1
  return 0
}

function withUnit(value: number, unit: string): string {
  return `${fixed(value, decimalsFor(value))} ${unit}`
}

/**
 * True when the value, rounded to the digits it would actually be shown with, has
 * reached the next unit.
 *
 * The unit has to be chosen from the rounded number, not from the raw one: 999,96 m
 * is below the kilometre and would be printed with one decimal, which rounds it to
 * "1.000,0 m" -- a unit the switch was supposed to prevent. The same goes for
 * 9.999,6 m², printed without decimals as "10.000 m²" where "1,00 ha" was meant.
 */
function reachesNextUnit(value: number, limit: number): boolean {
  return Number(value.toFixed(decimalsFor(value))) >= limit
}

/** Metres below a kilometre, kilometres above it. */
export function formatDistance(meters: number): string {
  if (!Number.isFinite(meters) || meters <= 0) return '0 m'
  if (meters < 1000 && !reachesNextUnit(meters, 1000)) return withUnit(meters, 'm')
  return withUnit(meters / 1000, 'km')
}

/**
 * Square metres up to a hectare, then hectares, then square kilometres.
 *
 * Hectares in the middle because that is the unit land is talked about in here -- a
 * plot of 24.500 m² tells nobody anything, 2,45 ha does.
 */
export function formatArea(squareMeters: number): string {
  if (!Number.isFinite(squareMeters) || squareMeters <= 0) return '0 m²'
  if (squareMeters < 10_000 && !reachesNextUnit(squareMeters, 10_000)) {
    return withUnit(squareMeters, 'm²')
  }

  // A square kilometre is a hundred hectares, so the second boundary is checked in
  // hectares -- the unit the number would be printed in.
  const hectares = squareMeters / 10_000
  if (hectares < 100 && !reachesNextUnit(hectares, 100)) return withUnit(hectares, 'ha')
  return withUnit(squareMeters / 1_000_000, 'km²')
}
