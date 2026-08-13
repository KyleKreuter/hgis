/**
 * The camera the hidden export map is put on.
 *
 * Centre, bearing and pitch are copied from the visible map unchanged. The zoom is not:
 * the page has a different shape and a different size than the map panel, and keeping the
 * screen's zoom would crop or widen the view by however much the two happen to differ.
 *
 * The export zoom is chosen so the whole screen view fits inside the page. Scaling by the
 * *smaller* of the two ratios is what makes that true in both directions at once -- and
 * it holds for a rotated or tilted map as well, because scaling the viewport rectangle
 * around its own centre contains the old rectangle whatever angle its content sits at.
 *
 * The consequence is the one CONTRACT.md 13.1 warns about: the export's scale is not the
 * screen's. A4 portrait from a wide map panel zooms out; a big page from a narrow panel
 * zooms in. The scale bar has to be computed from *this* zoom, never read off the screen.
 */

import type { ScreenSize } from './pageFormat'

/**
 * MapLibre's own default ceiling. Fitting a large page from a small panel can ask for
 * more zoom than the map has; the camera would clamp anyway, and clamping here keeps the
 * zoom used for the scale bar equal to the zoom the map is actually on.
 */
export const MAX_EXPORT_ZOOM = 22

/**
 * @param screenZoom the visible map's zoom
 * @param screen the visible map's box in CSS pixels
 * @param target the export's box in CSS pixels (`cssWidth`/`cssHeight`, not the file size)
 */
export function exportZoom(screenZoom: number, screen: ScreenSize, target: ScreenSize): number {
  // A panel that has not been laid out yet reports zero. There is no ratio to compute
  // from that, and the screen's own zoom is the only honest answer.
  if (screen.width <= 0 || screen.height <= 0) return screenZoom
  if (target.width <= 0 || target.height <= 0) return screenZoom

  const factor = Math.min(target.width / screen.width, target.height / screen.height)
  return Math.min(screenZoom + Math.log2(factor), MAX_EXPORT_ZOOM)
}
