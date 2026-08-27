import type { BasemapCatalogEntry } from '@/api/basemaps'

export interface BasemapGroup {
  group: string
  entries: BasemapCatalogEntry[]
}

/**
 * Groups the catalog by its `group` field (VERTRAG.md), in the order each group first
 * appears in the catalog. Nothing here re-sorts by a hardcoded list of the six group
 * names the contract names today -- a group the server adds later still gets its own
 * section, and the server is trusted to already list "Standard" before "Bundesländer".
 */
export function groupBasemaps(catalog: readonly BasemapCatalogEntry[]): BasemapGroup[] {
  const groups: BasemapGroup[] = []
  const byName = new Map<string, BasemapGroup>()

  for (const entry of catalog) {
    let group = byName.get(entry.group)
    if (!group) {
      group = { group: entry.group, entries: [] }
      byName.set(entry.group, group)
      groups.push(group)
    }
    group.entries.push(entry)
  }

  return groups
}
