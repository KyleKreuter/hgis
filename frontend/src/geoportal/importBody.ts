/**
 * Builds the `POST /api/projects/{projectId}/geoportal-imports` body (CONTRACT.md 11.6)
 * from the dialog's own state. Kept separate from the component so the three optional
 * fields -- each with its own "when is this actually sent" rule -- are covered by tests
 * instead of only by looking at the form.
 */

import type { StartGeoportalImportInput } from '@/api/geoportal'

/** Trims the custom name; blank means "let the server fall back to the dataset title". */
function nameParam(name: string): string | undefined {
  const trimmed = name.trim()
  return trimmed === '' ? undefined : trimmed
}

/** Only sent while the "aktueller Kartenausschnitt" toggle is on and a bbox is known. */
function bboxParam(
  useMapExtent: boolean,
  mapBbox: [number, number, number, number] | null,
): [number, number, number, number] | undefined {
  return useMapExtent && mapBbox ? mapBbox : undefined
}

/**
 * Every field selected is exactly what "kein besonderer Wunsch" means (decision E2: all
 * pre-checked) -- sent as `undefined` so the request matches the contract's own "absent
 * means every field" instead of spelling out a list the server would have produced
 * anyway. Checked against every name in `allFieldNames` rather than against the set's
 * size, so a selection that happens to carry a stale entry never masks an unchecked
 * field. Field order follows the dataset's own field order, not `Set` iteration order,
 * so two calls with the same checkboxes build byte-identical bodies.
 */
function fieldsParam(
  allFieldNames: readonly string[],
  selectedFields: ReadonlySet<string>,
): string[] | undefined {
  const allSelected = allFieldNames.every((name) => selectedFields.has(name))
  if (allSelected) return undefined
  return allFieldNames.filter((name) => selectedFields.has(name))
}

export interface GeoportalImportSelection {
  datasetId: string
  name: string
  allFieldNames: readonly string[]
  selectedFields: ReadonlySet<string>
  useMapExtent: boolean
  mapBbox: [number, number, number, number] | null
}

export function buildGeoportalImportBody(selection: GeoportalImportSelection): StartGeoportalImportInput {
  return {
    datasetId: selection.datasetId,
    name: nameParam(selection.name),
    bbox: bboxParam(selection.useMapExtent, selection.mapBbox),
    fields: fieldsParam(selection.allFieldNames, selection.selectedFields),
  }
}
