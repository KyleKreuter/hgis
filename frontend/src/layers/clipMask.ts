import type { ClipMode, GeometryType } from '@/api/layers'

/**
 * Geometry kinds that can hold a clip mask (CONTRACT.md phase 19, unchanged in phase
 * 20 and 21). A point or line layer as a mask would clip every layer above it to
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
 * the resting state every layer starts in. The four real modes are offered only for
 * the two kinds `canBeClipMask` allows; the submenu trigger itself stays disabled for
 * the rest (`clipMaskLockedReason`), so this array staying `[null]` there is a
 * defensive fallback, not the primary lock.
 */
export function availableClipModes(geometryType: GeometryType): (ClipMode | null)[] {
  return canBeClipMask(geometryType)
    ? [null, 'insideWhole', 'insideClipped', 'outsideWhole', 'outsideClipped']
    : [null]
}

/** Label for one clip-mode choice in the action menu's radio group (CONTRACT.md phase 21 wording). */
const CLIP_MODE_LABELS: Record<ClipMode, string> = {
  insideWhole: 'Nur innerhalb',
  insideClipped: 'Nur innerhalb + geschnitten',
  outsideWhole: 'Nur außerhalb',
  outsideClipped: 'Nur außerhalb + geschnitten',
}

export function clipModeLabel(mode: ClipMode | null): string {
  return mode === null ? 'Kein Zuschnitt' : CLIP_MODE_LABELS[mode]
}

/** aria-label for the layer-tree badge marking a layer as a clip mask, by mode. */
const CLIP_MODE_BADGE_ARIA_LABELS: Record<ClipMode, string> = {
  insideWhole: 'Maske für den Zuschnitt, zeigt nur innerhalb, ganz',
  insideClipped: 'Maske für den Zuschnitt, zeigt nur innerhalb, geschnitten',
  outsideWhole: 'Maske für den Zuschnitt, zeigt nur außerhalb, ganz',
  outsideClipped: 'Maske für den Zuschnitt, zeigt nur außerhalb, geschnitten',
}

export function clipMaskBadgeAriaLabel(mode: ClipMode): string {
  return CLIP_MODE_BADGE_ARIA_LABELS[mode]
}

/**
 * The direction sentence inside the badge tooltip (`clipMaskBadgeTooltip`). The two
 * `*Whole` modes name the boundary rule in the same breath (contract "Grenzregel"):
 * an object touching the mask counts as inside, so it is what keeps `insideWhole` and
 * `outsideWhole` complementary instead of leaving a gap at the boundary.
 */
const CLIP_MODE_DIRECTIONS: Record<ClipMode, string> = {
  insideWhole: 'Zeigt nur, was innerhalb liegt, ganz. Ein Objekt an der Grenze zählt als innerhalb.',
  insideClipped: 'Zeigt nur den Teil, der innerhalb liegt.',
  outsideWhole: 'Zeigt nur, was außerhalb liegt, ganz. Ein Objekt an der Grenze zählt als innerhalb.',
  outsideClipped: 'Zeigt nur den Teil, der außerhalb liegt.',
}

/**
 * Tooltip for the same badge. Always names the mode, and always closes on the same
 * reminder: the mask keeps clipping while its own layer is hidden (contract "Eine
 * unsichtbar geschaltete Maske wirkt weiter") -- the one fact the badge exists to
 * explain, since nothing else on the row survives the layer being switched off.
 */
export function clipMaskBadgeTooltip(mode: ClipMode): string {
  return `Maske für den Zuschnitt. ${CLIP_MODE_DIRECTIONS[mode]} Wirkt auch, wenn der Layer ausgeblendet ist.`
}
