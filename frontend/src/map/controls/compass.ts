/**
 * The decisions the compass and the reset button make, kept out of the components so
 * they are testable without WebGL -- the same split `imageExport/furniture.ts` uses.
 */

/**
 * Below this, a bearing or pitch counts as "none".
 *
 * MapLibre stores both as floats and a drag rarely lands on a clean zero: after
 * rotating back by hand a map sits at values like 0.0003. Comparing against zero would
 * leave the compass permanently lit and the reset button permanently enabled, which is
 * exactly the noise both are meant to remove.
 */
const ANGLE_EPSILON = 0.5

/** Normalizes any bearing to the -180..180 range MapLibre reports and CSS rotates by. */
export function normalizeBearing(bearing: number): number {
  const wrapped = ((bearing % 360) + 360) % 360
  return wrapped > 180 ? wrapped - 360 : wrapped
}

/** Whether the map is tilted enough to be worth drawing the pitch bar. */
export function isTilted(pitch: number): boolean {
  return Math.abs(pitch) >= ANGLE_EPSILON
}

/** Whether the map is turned or tilted enough to be worth showing and resetting. */
export function isViewOriented(bearing: number, pitch: number): boolean {
  return Math.abs(normalizeBearing(bearing)) >= ANGLE_EPSILON || isTilted(pitch)
}

/**
 * How full the pitch bar is drawn, 0 to 1.
 *
 * Clamped rather than trusted: MapLibre's default ceiling is 60 degrees, but a project
 * that raises `maxPitch` would otherwise push the bar past its own button.
 */
export function pitchFraction(pitch: number, maxPitch = 60): number {
  if (maxPitch <= 0) return 0
  return Math.min(Math.max(pitch, 0) / maxPitch, 1)
}

/**
 * What the compass button says it does. Spelled out rather than a bare "Norden", since
 * the button resets both angles and a user who only tilted would not expect a control
 * labelled after the other one.
 */
export function compassLabel(bearing: number, pitch: number): string {
  const turned = Math.abs(normalizeBearing(bearing)) >= ANGLE_EPSILON
  const tilted = Math.abs(pitch) >= ANGLE_EPSILON
  if (turned && tilted) return 'Norden oben, Neigung zurücksetzen'
  if (tilted) return 'Neigung zurücksetzen'
  return 'Norden oben'
}
