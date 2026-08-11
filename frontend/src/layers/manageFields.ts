import type { AddLayerFieldInput, FieldType, LayerFieldUsage, RenameLayerFieldInput } from '@/api/layers'
import { formatCount } from '@/lib/format'

/**
 * The subset of {@link import('@/api/layers').LayerField} the duplicate check needs --
 * kept narrow so a test can pass plain literals instead of a full field.
 */
export interface ExistingField {
  id: string
  sourceName: string
  columnName: string
}

/**
 * Whether `name` collides with an already-persisted field of the layer, checked against
 * both `sourceName` and `columnName` (case-insensitive, after trim).
 *
 * On the server, `LayerFields.find`/`require` resolve a name through either column,
 * `sourceName` winning on a tie (CONTRACT.md "Falle 2") -- renaming into another
 * field's `columnName` would silently retarget every later lookup of that name onto the
 * wrong field, with knock-on effects for style, filter and sort. `excludeId` is the
 * field being renamed itself: keeping its own previous name is a no-op, not a collision.
 *
 * Deliberately separate from `fieldNameErrors` in `createLayer.ts`, which only ever
 * compares within one batch of not-yet-created draft fields and knows nothing about
 * `columnName` -- bending it to also check persisted fields would have made it serve
 * two different questions for one caller's convenience.
 */
export function existingFieldNameError(
  name: string,
  existing: readonly ExistingField[],
  excludeId?: string,
): string | undefined {
  const trimmed = name.trim()
  if (!trimmed) return 'Pflichtfeld'
  const lower = trimmed.toLowerCase()
  const collides = existing.some(
    (field) =>
      field.id !== excludeId &&
      (field.sourceName.trim().toLowerCase() === lower ||
        field.columnName.trim().toLowerCase() === lower),
  )
  return collides ? 'Feldname bereits vergeben' : undefined
}

/** Builds the POST body for adding a field: trims the name, keeps the type as chosen. */
export function buildAddFieldInput(name: string, type: FieldType): AddLayerFieldInput {
  return { name: name.trim(), type }
}

/** Builds the PATCH body for a rename: trims the name, keeps the field id it targets. */
export function buildRenameFieldInput(fieldId: string, name: string): RenameLayerFieldInput {
  return { fieldId, name: name.trim() }
}

/**
 * The confirmation text for deleting a field (CONTRACT.md "Attributfelder löschen"):
 * how many objects lose a value, and -- only when it actually applies -- that the style
 * resets as a side effect. That consequence is easy to miss and nobody expects it, so it
 * belongs in the question asked up front rather than a surprise discovered afterwards.
 */
export function buildDeleteFieldWarning(usage: LayerFieldUsage): string {
  const sentences = [
    usage.valueCount === 0
      ? 'Kein Objekt hat einen Wert in diesem Feld.'
      : `${formatCount(usage.valueCount)} ${usage.valueCount === 1 ? 'Objekt hat' : 'Objekte haben'} einen Wert in diesem Feld.`,
  ]
  if (usage.usedByRenderer) {
    sentences.push('Die Einfärbung nach diesem Feld wird dabei zurückgesetzt.')
  }
  if (usage.usedByLabels) {
    sentences.push('Die Beschriftung nach diesem Feld wird dabei deaktiviert.')
  }
  return sentences.join(' ')
}
