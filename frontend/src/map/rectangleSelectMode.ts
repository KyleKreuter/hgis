import type { SelectionMode } from '@/state/selection'

/**
 * Which selection mode a rectangle drag resolves to, from the modifier keys held at
 * release -- ersetzen without a key, ergänzen with Shift, abziehen with Alt. The same
 * convention as most desktop GIS tools. Ctrl/Cmd is left alone deliberately: the
 * browser and the OS already claim it for other things.
 *
 * When both are held, Shift wins -- an arbitrary but fixed tie-break, since holding
 * both at once is not a combination the tool defines a meaning for.
 */
export function modifierSelectionMode(event: { shiftKey: boolean; altKey: boolean }): SelectionMode {
  if (event.shiftKey) return 'add'
  if (event.altKey) return 'subtract'
  return 'replace'
}
