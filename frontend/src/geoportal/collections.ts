/**
 * The collection choice a service entry demands (CONTRACT.md 11.9).
 *
 * A service whose collections are the object classes of one data model is listed as a
 * single catalog entry -- `xplan` alone stands for 247 collections. Such an entry names
 * no collection, so the detail pane has to ask for one before an import can start.
 */

import type { GeoportalCollection, GeoportalDatasetSummary } from '@/api/geoportal'

/**
 * Whether this entry stands for a whole service rather than for one collection.
 * `collectionCount > 1` is the contract's only signal for it (11.9), so it is the only
 * thing tested here -- the 20-collection threshold that decides the shape is the
 * backend's business and never reaches the client.
 */
export function isServiceEntry(dataset: GeoportalDatasetSummary): boolean {
  return dataset.collectionCount > 1
}

/**
 * Whether the dialog still owes the user a collection choice: a service entry with
 * nothing chosen yet. False for a flat entry, whose id already names a collection.
 */
export function needsCollectionChoice(
  dataset: GeoportalDatasetSummary | null,
  chosenCollectionId: string | null,
): boolean {
  return dataset !== null && isServiceEntry(dataset) && chosenCollectionId === null
}

/**
 * The id the detail call and the import must name. A service id alone is a `400` on the
 * import (11.9), so the chosen collection wins over the entry the user clicked in the
 * list. Taken from the choice itself rather than from the detail response, so a server
 * that echoes something else can never decide what gets imported.
 */
export function activeDatasetId(
  selectedId: string | null,
  chosenCollectionId: string | null,
): string | null {
  return chosenCollectionId ?? selectedId
}

/**
 * The picker's match rule: title only, substring, case-insensitive. Same shape as the
 * catalog search (`search.ts`), minus the fields a collection does not carry -- the
 * contract gives it a title and nothing else.
 */
export function matchesCollectionQuery(collection: GeoportalCollection, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase('de-DE')
  if (needle === '') return true
  return collection.title.toLocaleLowerCase('de-DE').includes(needle)
}

/**
 * The collections to show: matching the search, alphabetical by German collation.
 *
 * Sorted here rather than trusted from the response, because the contract promises no
 * order for `collections` and 247 entries in service order are 247 entries in no order
 * the user can follow.
 */
export function visibleCollections(
  collections: readonly GeoportalCollection[],
  query: string,
): GeoportalCollection[] {
  return collections
    .filter((collection) => matchesCollectionQuery(collection, query))
    .sort((a, b) => a.title.localeCompare(b.title, 'de-DE'))
}
