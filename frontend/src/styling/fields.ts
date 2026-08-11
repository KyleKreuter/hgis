/**
 * Field handling for the symbology panel.
 *
 * **Every `field` in a style is the column name, never the source name.** The tile
 * carries its properties under the column name -- a source name may hold umlauts or
 * blanks and never becomes an SQL identifier -- so the server canonicalises
 * `renderer.field` and `labels.field` on save and answers `/values` and `/classify`
 * with the column name too. The field pickers therefore carry `columnName` as their
 * value and only *display* `sourceName`; writing anything else would make the style in
 * the cache disagree with the stored one, and `['get', field]` would read a property
 * that is not in the tile.
 */

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

/**
 * How a value from `/values` reads in the category list.
 *
 * Accepts `undefined` beside `null` and reads both as "ohne Wert". The server writes
 * `value` on every category, null included, so `undefined` does not arrive today -- but
 * the two mean the same thing here either way: the category MapLibre will never match,
 * whose objects fall to the fallback symbol.
 */
export function formatCategoryValue(value: CategoryValue | undefined): string {
  if (value === null || value === undefined) return 'ohne Wert'
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
