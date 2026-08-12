import type { GeometryType, LayerSummary } from '@/api/layers'

/**
 * Geometry kinds that can hold the clip mask (CONTRACT.md phase 19). A point or line
 * layer as a mask would clip every layer above it to nothing -- `ST_Intersection`
 * against a point or line geometry is empty in practice -- so the action menu offers
 * the entry everywhere but disables it outside these two kinds, with a reason attached
 * (`clipMaskLockedReason`), never by leaving it out silently.
 */
const MASKABLE_GEOMETRY_TYPES: readonly GeometryType[] = ['MULTIPOLYGON', 'GEOMETRY']

export function canBeClipMask(geometryType: GeometryType): boolean {
  return MASKABLE_GEOMETRY_TYPES.includes(geometryType)
}

/** Why the "Als Zuschnitt für alles darüber" entry is locked, or `null` when it is not. */
export function clipMaskLockedReason(geometryType: GeometryType): string | null {
  return canBeClipMask(geometryType) ? null : 'Nur Flächen oder gemischte Geometrien taugen als Maske'
}

/**
 * The project's current mask, if `layers` holds one other than `excludingLayerId`.
 *
 * Marking a layer as the mask unmarks whichever layer held it before (contract
 * "Höchstens eine Maske je Projekt", enforced server-side). The client already has
 * every layer's `clipMask` in the list it diffs the tree against, so it can name that
 * previous holder itself instead of waiting on a server response shaped for it.
 */
export function findOtherClipMask(
  layers: LayerSummary[],
  excludingLayerId: string,
): LayerSummary | null {
  return layers.find((layer) => layer.id !== excludingLayerId && layer.clipMask) ?? null
}

/** Info toast shown after marking a layer as the mask demotes a previous one. */
export function clipMaskReplacedMessage(previousMask: LayerSummary): string {
  return `„${previousMask.name}" ist keine Maske mehr`
}
