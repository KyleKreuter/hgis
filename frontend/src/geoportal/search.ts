/**
 * Search and filtering for the catalog dialog (plan 6.5, Schritt 2 and 3).
 *
 * The whole catalog arrives in one response (CONTRACT.md 11.2), so every one of these
 * runs against the array already held in memory -- typing in the search field costs no
 * round trip, which is the entire point of shipping the catalog in one piece.
 */

import type { GeoportalDatasetKind, GeoportalDatasetSummary } from '@/api/geoportal'

/**
 * The two "Art" checkboxes above the list. `BOTH` counts as `'features'`: it offers real
 * objects, and only a service with nothing but a map image (`WMS`) is "nur Kartenbild".
 */
export type DatasetKindFilter = 'features' | 'imageOnly'

const ALL_KIND_FILTERS: readonly DatasetKindFilter[] = ['features', 'imageOnly']

export function defaultKindFilters(): Set<DatasetKindFilter> {
  return new Set(ALL_KIND_FILTERS)
}

function kindFilterOf(kind: GeoportalDatasetKind): DatasetKindFilter {
  return kind === 'WMS' ? 'imageOnly' : 'features'
}

export interface GeoportalFilters {
  query: string
  kinds: ReadonlySet<DatasetKindFilter>
  /** null means "alle Themen". */
  topic: string | null
  /** null means "alle Behörden". */
  agency: string | null
}

/** Both "Art" boxes checked, no topic, no agency -- the whole catalog, untouched. */
export function defaultGeoportalFilters(): GeoportalFilters {
  return { query: '', kinds: defaultKindFilters(), topic: null, agency: null }
}

/**
 * The search field's match rule: name, description and agency, substring, case-
 * insensitive (plan 6.5, "Der Nutzer tippt 'baum' und sieht sofort das
 * Straßenbaumkataster"). A plain substring test on purpose -- a word-boundary search
 * would miss exactly that example.
 */
export function matchesQuery(dataset: GeoportalDatasetSummary, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase('de-DE')
  if (needle === '') return true
  const haystacks = [dataset.title, dataset.description, dataset.agency]
  return haystacks.some((value) => value?.toLocaleLowerCase('de-DE').includes(needle) ?? false)
}

export function filterDatasets(
  datasets: readonly GeoportalDatasetSummary[],
  filters: GeoportalFilters,
): GeoportalDatasetSummary[] {
  return datasets.filter(
    (dataset) =>
      matchesQuery(dataset, filters.query) &&
      filters.kinds.has(kindFilterOf(dataset.kind)) &&
      (filters.topic === null || dataset.topic === filters.topic) &&
      (filters.agency === null || dataset.agency === filters.agency),
  )
}

/** "Darunter steht die vollständige Liste, nach Namen sortiert" (plan 6.5, Schritt 2). */
export function sortByTitle(datasets: readonly GeoportalDatasetSummary[]): GeoportalDatasetSummary[] {
  return [...datasets].sort((a, b) => a.title.localeCompare(b.title, 'de-DE'))
}

function distinctValues(
  datasets: readonly GeoportalDatasetSummary[],
  pick: (dataset: GeoportalDatasetSummary) => string | null,
): string[] {
  const values = new Set<string>()
  for (const dataset of datasets) {
    const value = pick(dataset)
    if (value !== null) values.add(value)
  }
  return [...values].sort((a, b) => a.localeCompare(b, 'de-DE'))
}

/** Options for the "Thema" dropdown, alphabetical, without the datasets that carry none. */
export function distinctTopics(datasets: readonly GeoportalDatasetSummary[]): string[] {
  return distinctValues(datasets, (dataset) => dataset.topic)
}

/** Options for the "Behörde" dropdown, alphabetical, without the datasets that carry none. */
export function distinctAgencies(datasets: readonly GeoportalDatasetSummary[]): string[] {
  return distinctValues(datasets, (dataset) => dataset.agency)
}
