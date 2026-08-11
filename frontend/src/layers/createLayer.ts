import type { CreatableGeometryType, CreateLayerInput, FieldType } from '@/api/layers'

/** Server rejects more than this many fields per layer (CONTRACT.md). */
export const MAX_FIELDS = 50

/** The three geometry kinds this dialog offers -- `GEOMETRY` (mixed) is not one of them, see api/layers.ts. */
export const CREATABLE_GEOMETRY_TYPES: CreatableGeometryType[] = [
  'MULTIPOINT',
  'MULTILINESTRING',
  'MULTIPOLYGON',
]

/** Plain-language labels for the fixed `FieldType` enum -- CONTRACT.md table. */
export const FIELD_TYPE_LABELS: Record<FieldType, string> = {
  TEXT: 'Text',
  INTEGER: 'Ganzzahl',
  BIGINT: 'große Ganzzahl',
  DOUBLE: 'Dezimalzahl',
  NUMERIC: 'exakte Dezimalzahl',
  BOOLEAN: 'Ja/Nein',
  DATE: 'Datum',
  TIME: 'Uhrzeit',
  TIMESTAMP: 'Zeitpunkt',
}

export const FIELD_TYPE_OPTIONS: FieldType[] = [
  'TEXT',
  'INTEGER',
  'BIGINT',
  'DOUBLE',
  'NUMERIC',
  'BOOLEAN',
  'DATE',
  'TIME',
  'TIMESTAMP',
]

/** One row of the dialog's dynamic field list, before it is trimmed into a `CreateLayerField`. */
export interface DraftField {
  /** Stable key for the row, independent of its position -- rows get removed from the middle. */
  id: string
  name: string
  type: FieldType
}

/**
 * Per-row error for the field list, aligned index-for-index with `fields`; `undefined`
 * where the row is fine.
 *
 * Duplicate names (case-insensitive after trim) flag every row that shares one, not just
 * the second occurrence -- it is not obvious to the user which of the two rows counts as
 * "the" duplicate, so both need to change. Mirrors the server's own comparison
 * (CONTRACT.md): `SqlIdentifier.toColumnName` would otherwise turn two rows named "Art"
 * into `art` and `art_1`, silently showing the same label twice with different behaviour.
 */
export function fieldNameErrors(fields: readonly { name: string }[]): (string | undefined)[] {
  const trimmedLower = fields.map((field) => field.name.trim().toLowerCase())
  const counts = new Map<string, number>()
  for (const name of trimmedLower) {
    if (name) counts.set(name, (counts.get(name) ?? 0) + 1)
  }
  return trimmedLower.map((name) => {
    if (!name) return 'Pflichtfeld'
    if ((counts.get(name) ?? 0) > 1) return 'Feldname bereits vergeben'
    return undefined
  })
}

/**
 * Whether the dialog may submit: a non-blank layer name, at most {@link MAX_FIELDS} rows,
 * and no per-row problem from {@link fieldNameErrors}. Mirrors the server-side checks
 * (CONTRACT.md) so a rejected submission is the exception, not the common case.
 */
export function canSubmitLayer(name: string, fields: readonly { name: string }[]): boolean {
  if (!name.trim()) return false
  if (fields.length > MAX_FIELDS) return false
  return fieldNameErrors(fields).every((error) => error === undefined)
}

/** Builds the POST body from the dialog's raw state: trims the layer name and every field name. */
export function buildCreateLayerInput(
  name: string,
  geometryType: CreatableGeometryType,
  fields: readonly { name: string; type: FieldType }[],
): CreateLayerInput {
  return {
    name: name.trim(),
    geometryType,
    fields: fields.map((field) => ({ name: field.name.trim(), type: field.type })),
  }
}
