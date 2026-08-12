import type { ClipMode, GeometryType, LayerSummary } from '@/api/layers'

/**
 * Geometry kinds that can hold the clip mask (CONTRACT.md phase 19, unchanged in
 * phase 20). A point or line layer as a mask would clip every layer above it to
 * nothing -- `ST_Intersection`/`ST_Difference` against a point or line geometry is
 * empty in practice -- so the action menu offers the submenu everywhere but disables
 * it outside these two kinds, with a reason attached (`clipMaskLockedReason`), never
 * by leaving it out silently.
 */
const MASKABLE_GEOMETRY_TYPES: readonly GeometryType[] = ['MULTIPOLYGON', 'GEOMETRY']

export function canBeClipMask(geometryType: GeometryType): boolean {
  return MASKABLE_GEOMETRY_TYPES.includes(geometryType)
}

/** Why the "Zuschnitt für alles darüber" submenu is locked, or `null` when it is not. */
export function clipMaskLockedReason(geometryType: GeometryType): string | null {
  return canBeClipMask(geometryType) ? null : 'Nur Flächen oder gemischte Geometrien taugen als Maske'
}

/**
 * The clip mode choices the action menu's radio group offers for `geometryType`.
 * `null` ("Kein Zuschnitt") is always offered -- it costs no geometry check and is
 * the resting state every layer starts in. `'inside'`/`'outside'` are offered only
 * for the two kinds `canBeClipMask` allows; the submenu trigger itself stays
 * disabled for the rest (`clipMaskLockedReason`), so this array staying `[null]`
 * there is a defensive fallback, not the primary lock.
 */
export function availableClipModes(geometryType: GeometryType): (ClipMode | null)[] {
  return canBeClipMask(geometryType) ? [null, 'inside', 'outside'] : [null]
}

/** Label for one clip-mode choice in the action menu's radio group (CONTRACT.md phase 20 wording). */
export function clipModeLabel(mode: ClipMode | null): string {
  if (mode === 'inside') return 'Nur innerhalb zeigen'
  if (mode === 'outside') return 'Nur außerhalb zeigen'
  return 'Kein Zuschnitt'
}

/** aria-label for the layer-tree badge marking a layer as the project's mask, by direction. */
export function clipMaskBadgeAriaLabel(mode: ClipMode): string {
  return mode === 'inside' ? 'Maske für den Zuschnitt, zeigt nur innerhalb' : 'Maske für den Zuschnitt, zeigt nur außerhalb'
}

/**
 * Tooltip for the same badge. Always names the direction, and always closes on the
 * same reminder: the mask keeps clipping while its own layer is hidden (contract
 * "Eine unsichtbar geschaltete Maske wirkt weiter") -- the one fact the badge exists
 * to explain, since nothing else on the row survives the layer being switched off.
 */
export function clipMaskBadgeTooltip(mode: ClipMode): string {
  const direction = mode === 'inside' ? 'Zeigt nur, was innerhalb liegt.' : 'Zeigt nur, was außerhalb liegt.'
  return `Maske für den Zuschnitt. ${direction} Wirkt auch, wenn der Layer ausgeblendet ist.`
}

/**
 * The project's current mask, if `layers` holds one other than `excludingLayerId`.
 *
 * Picking a clip mode for a layer clears whichever layer held one before (contract
 * "Höchstens eine Maske je Projekt", enforced server-side). The client already has
 * every layer's `clipMode` in the list it diffs the tree against, so it can name that
 * previous holder itself instead of waiting on a server response shaped for it.
 */
export function findOtherClipMask(
  layers: LayerSummary[],
  excludingLayerId: string,
): LayerSummary | null {
  return layers.find((layer) => layer.id !== excludingLayerId && layer.clipMode != null) ?? null
}

/** Info toast shown after marking a layer as the mask demotes a previous one. */
export function clipMaskReplacedMessage(previousMask: LayerSummary): string {
  return `„${previousMask.name}" ist keine Maske mehr`
}
