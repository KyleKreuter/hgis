import type { LayerField } from '@/api/layers'
import { formatAttributeNumber } from '@/lib/format'
import type { CategoryValue } from './types'

/**
 * Numeric PostgreSQL types, spelled the way `layer_field.data_type` records them.
 * Only these can be graduated -- the server rejects anything else with a 400, and
 * offering the field would be an invitation to run into that.
 */
const NUMERIC_TYPES = new Set([
  'smallint',
  'integer',
  'bigint',
  'decimal',
  'numeric',
  'real',
  'double precision',
])

export function isNumericField(field: LayerField): boolean {
  // numeric(10,2) and friends carry their precision in the type name.
  const base = field.dataType.toLowerCase().replace(/\(.*/, '').trim()
  return NUMERIC_TYPES.has(base)
}

/** How a value from `/values` reads in the category list. */
export function formatCategoryValue(value: CategoryValue): string {
  if (value === null) return 'ohne Wert'
  if (typeof value === 'number') return formatAttributeNumber(value)
  return value === '' ? 'leer' : value
}

/**
 * Label of one class, e.g. "120 – 340". An en dash with spaces, not a hyphen: the
 * bounds can be negative, and "-5 - 3" is unreadable.
 */
export function formatClassLabel(min: number, max: number): string {
  return `${formatAttributeNumber(min)} – ${formatAttributeNumber(max)}`
}
