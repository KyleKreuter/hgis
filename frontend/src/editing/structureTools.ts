import { ApiError } from '@/api/client'
import type { GeometryType } from '@/api/layers'
import { MERGE_MAX_FEATURES, MERGE_MIN_FEATURES } from '@/api/structure'
import { formatCount } from '@/lib/format'

/**
 * The rules the two structural tools follow, as plain functions.
 *
 * Kept out of the components on purpose, the same reasoning `drawTools.ts` gives for
 * `toolsFor`: which tool a layer offers, when it is locked, and what a refusal reads
 * like are decisions worth testing without rendering anything -- and the components
 * that show them (`StructureToolbar`, `MergeDialog`) would otherwise each carry their
 * own copy.
 */
export type StructureTool = 'split' | 'merge'

/**
 * Which structural tools a layer offers at all.
 *
 * A point has no inside to cut and no area to unite, so a `MULTIPOINT` layer gets
 * neither -- and gets them *hidden* rather than disabled, unlike the drawing tools: a
 * greyed-out scissors on a point layer would suggest the operation exists there and is
 * merely unavailable right now. On a `GEOMETRY` layer the column says nothing about the
 * individual row, so the check moves to the feature itself -- see `splitObjection`.
 */
export function structureToolsFor(geometryType: GeometryType): StructureTool[] {
  return geometryType === 'MULTIPOINT' ? [] : ['split', 'merge']
}

/**
 * Why neither tool may be used right now, or null when both may.
 *
 * Both write straight through to the server, so an edit buffer holding anything would be
 * stale the moment they did -- CONTRACT.md 12 requires the client to refuse in that case.
 * `pendingChanges` is the same count the leave guard blocks on (`hasPendingWork` in
 * `routes/projects.$projectId.tsx`), covering the map's draft buffer and the table's cell
 * edits alike.
 *
 * A drawing session with an empty buffer is the second case: nothing is unsaved, but
 * the drawing surface holds its own copy of every loaded feature, and a split behind its
 * back would leave it editing a row that no longer looks like that.
 *
 * The pending count comes first because it is the more informative of the two: it names
 * work that would be lost, not merely a mode to leave.
 */
export function structureLockReason(pendingChanges: number, drawingActive: boolean): string | null {
  if (pendingChanges > 0) {
    return 'Speichern oder verwerfen Sie zuerst Ihre Änderungen. Teilen und Zusammenführen schreiben sofort auf dem Server.'
  }
  if (drawingActive) {
    return 'Beenden Sie zuerst den Zeichenmodus. Teilen und Zusammenführen arbeiten auf dem gespeicherten Stand.'
  }
  return null
}

/** Why the split tool cannot be started, or null when it can. `lock` wins over the selection. */
export function splitBlockReason(selectedCount: number, lock: string | null): string | null {
  if (lock) return lock
  if (selectedCount === 0) return 'Wählen Sie zuerst ein Objekt aus.'
  if (selectedCount > 1) {
    return `Teilen betrifft genau ein Objekt. Sie haben ${formatCount(selectedCount)} ausgewählt.`
  }
  return null
}

/** Why the merge tool cannot be started, or null when it can. `lock` wins over the selection. */
export function mergeBlockReason(selectedCount: number, lock: string | null): string | null {
  if (lock) return lock
  if (selectedCount < MERGE_MIN_FEATURES) {
    return 'Wählen Sie mindestens zwei Objekte aus.'
  }
  if (selectedCount > MERGE_MAX_FEATURES) {
    return `Das Programm führt höchstens ${formatCount(MERGE_MAX_FEATURES)} Objekte zusammen. Sie haben ${formatCount(selectedCount)} ausgewählt.`
  }
  return null
}

/** The three kinds a geometry can be, whether it is single or multi. */
export type GeometryKind = 'point' | 'line' | 'area'

/** The kind of a GeoJSON geometry type, or null for one this application never stores. */
export function geometryKindOf(type: string | undefined | null): GeometryKind | null {
  switch (type) {
    case 'Point':
    case 'MultiPoint':
      return 'point'
    case 'LineString':
    case 'MultiLineString':
      return 'line'
    case 'Polygon':
    case 'MultiPolygon':
      return 'area'
    default:
      return null
  }
}

/**
 * Why this one feature cannot be split, or null when it can.
 *
 * The wording is the server's own (CONTRACT.md 12.1), so the answer reads the same
 * whether the client caught it or the request did. Checked here as well because on a
 * `GEOMETRY` layer only the feature itself says what it is, and refusing before a line
 * has been drawn is cheaper than refusing after.
 */
export function splitObjection(geometryType: string | undefined | null): string | null {
  return geometryKindOf(geometryType) === 'point' ? 'Punkte lassen sich nicht teilen.' : null
}

/**
 * Why these features cannot be merged, or null when they can. Same wording as the
 * server's, same reason as `splitObjection`.
 *
 * Points first: "Punkte lassen sich nicht zusammenführen" is the more precise answer for
 * a selection of nothing but points, and reporting mixed kinds there would send the user
 * looking for an odd one out that is not the problem.
 */
export function mergeObjection(geometryTypes: readonly (string | undefined | null)[]): string | null {
  const kinds = geometryTypes.map(geometryKindOf)
  if (kinds.includes('point')) return 'Punkte lassen sich nicht zusammenführen.'
  if (new Set(kinds).size > 1) {
    return 'Nur Objekte derselben Geometrieart lassen sich zusammenführen.'
  }
  return null
}

/** What the request needs per part: the fid it addresses and the xmin it was planned against. */
export interface VersionedFeature {
  fid: number
  rowVersion: string
}

/**
 * `{ "42": "8241", … }` for the merge body.
 *
 * String keys because JSON has no others -- writing the map with numeric keys and letting
 * `JSON.stringify` convert them would work by accident, and would stop working the moment
 * anything read the map back.
 */
export function mergeRowVersions(features: readonly VersionedFeature[]): Record<string, string> {
  return Object.fromEntries(features.map((feature) => [String(feature.fid), feature.rowVersion]))
}

/**
 * What to show the user when the server refused.
 *
 * A `409` is the one case worth rephrasing: the server states it as a row version
 * mismatch, which is true and says nothing to whoever is looking at the map. What
 * happened is that someone else got there first, and that -- plus the fact that nothing
 * was written -- is what the message says instead. Every other status keeps the server's
 * own wording, which names the actual cause (a line that misses the object, a mixed
 * selection, an invalid result) far better than any generic sentence could.
 */
export function structureErrorMessage(error: unknown, action: StructureTool): string {
  if (error instanceof ApiError && error.status === 409) {
    return action === 'split'
      ? 'Ein anderer Benutzer hat das Objekt inzwischen geändert. Das Programm hat nichts geteilt. Laden Sie die Ansicht neu und versuchen Sie es noch einmal.'
      : 'Ein anderer Benutzer hat eines der Objekte inzwischen geändert. Das Programm hat nichts zusammengeführt. Laden Sie die Ansicht neu und versuchen Sie es noch einmal.'
  }
  if (error instanceof ApiError) return error.message
  return action === 'split'
    ? 'Das Programm konnte das Objekt nicht teilen.'
    : 'Das Programm konnte die Objekte nicht zusammenführen.'
}
