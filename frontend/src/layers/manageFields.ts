import type { AddLayerFieldInput, FieldType, LayerFieldUsage, RenameLayerFieldInput } from '@/api/layers'
import { formatCount } from '@/lib/format'
import { FIELD_TYPE_LABELS } from './createLayer'

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

/**
 * German label for a raw PostgreSQL `data_type`, for the "Typ" column of the fields
 * table -- without it that column shows the wire value verbatim ("double precision"),
 * which is exactly what {@link FIELD_TYPE_LABELS} exists to hide from the create dialog.
 *
 * Covers more than {@link FIELD_TYPE_LABELS}: a layer built from an import can carry
 * column types the create dialog never offers (`smallint`, `real`, `decimal`, `uuid`,
 * `bytea`, timestamp variants). The `timestamp*` prefix check mirrors `kindOf` in
 * `table/fieldKind.ts` -- both exist to answer "what kind of field is this", one for an
 * editor widget and one for a label, and should keep recognizing the same types.
 *
 * An unrecognized type falls back to the raw value instead of an empty cell -- still
 * more useful than nothing.
 */
export function dataTypeLabel(dataType: string): string {
  const type = dataType.toLowerCase()
  if (type.startsWith('timestamp')) return FIELD_TYPE_LABELS.TIMESTAMP
  switch (type) {
    case 'text':
      return FIELD_TYPE_LABELS.TEXT
    case 'integer':
    case 'smallint':
      return FIELD_TYPE_LABELS.INTEGER
    case 'bigint':
      return FIELD_TYPE_LABELS.BIGINT
    case 'double precision':
    case 'real':
      return FIELD_TYPE_LABELS.DOUBLE
    case 'numeric':
    case 'decimal':
      return FIELD_TYPE_LABELS.NUMERIC
    case 'boolean':
      return FIELD_TYPE_LABELS.BOOLEAN
    case 'date':
      return FIELD_TYPE_LABELS.DATE
    case 'time':
      return FIELD_TYPE_LABELS.TIME
    case 'uuid':
      return 'Kennung'
    case 'bytea':
      return 'Binärdaten'
    default:
      return dataType
  }
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
    sentences.push('Das Programm setzt dabei die Einfärbung nach diesem Feld zurück.')
  }
  if (usage.usedByLabels) {
    sentences.push('Das Programm deaktiviert dabei die Beschriftung nach diesem Feld.')
  }
  return sentences.join(' ')
}
