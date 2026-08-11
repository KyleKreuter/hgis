/**
 * A MapLibre interaction handler, as far as this module cares: `doubleClickZoom`,
 * `dragPan` and their siblings all look like this.
 */
export interface ToggleableHandler {
  isEnabled: () => boolean
  enable: () => void
  disable: () => void
}

/**
 * Turns a handler off and hands back the undo for exactly that change.
 *
 * The undo restores what this call found, not what it wants: a handler that was
 * already off stays off. Measuring and drawing both disable the double-click zoom
 * (one ends a sketch on a double-click, the other a shape), and an unconditional
 * `enable()` in the measuring teardown re-armed the zoom under the drawing tool --
 * every double-click that closed a polygon zoomed the map as well.
 *
 * Idempotent, because React runs a cleanup exactly once but StrictMode likes to prove
 * otherwise.
 */
export function suspendHandler(handler: ToggleableHandler): () => void {
  const wasEnabled = handler.isEnabled()
  if (wasEnabled) handler.disable()

  let restored = false
  return () => {
    if (restored) return
    restored = true
    if (wasEnabled) handler.enable()
  }
}
