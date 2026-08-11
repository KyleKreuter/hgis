import type { LayerStyle } from './types'

/**
 * Whether a style is complete enough to be sent to the server.
 *
 * The panel has no draft state -- picking "kategorisiert" writes a renderer that has no
 * field yet, and the server rejects an unknown field name with a 400 (the same rule the
 * filter parser follows: an identifier is never taken on trust). Such an intermediate
 * state is previewed on the map and simply not saved; the first request goes out once a
 * field has been chosen.
 */
export function isPersistable(style: LayerStyle | null): boolean {
  if (style === null) return true
  if (style.renderer.type !== 'single' && !style.renderer.field) return false
  if (style.labels?.enabled && !style.labels.field) return false
  return true
}
