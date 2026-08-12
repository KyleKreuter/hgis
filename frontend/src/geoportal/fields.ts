import type { GeoportalDatasetKind } from '@/api/geoportal'

/** Shown in the field table's third column, next to Anzeigename and Typ. */
const VALUES_SHOWN = 3

/**
 * "A, B, C …" for a field's short value list, or an explicit "keine Werte" -- mirrors the
 * file import preview's rule of never leaving an empty state that could pass for a bug.
 */
export function formatFieldValues(values: readonly string[]): string {
  if (values.length === 0) return 'keine Werte'
  const shown = values.slice(0, VALUES_SHOWN).join(', ')
  return values.length > VALUES_SHOWN ? `${shown} …` : shown
}

/**
 * Whether phase 23 can turn this dataset into a layer (CONTRACT.md 11.2: "phase 23 can
 * import FEATURES and BOTH"). A `WMS`-only dataset has no objects to fetch until the
 * image path (stage 2) exists -- the dialog shows it, but offers no import button for it.
 */
export function isImportable(kind: GeoportalDatasetKind): boolean {
  return kind !== 'WMS'
}
