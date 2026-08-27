import { Badge } from '@/components/ui/badge'
import type { BasemapCatalogEntry, BasemapCoverage } from '@/api/basemaps'

/** ISO 3166-2:DE codes without the `DE-` prefix, exactly the sixteen VERTRAG.md names
 * as valid `coverage` values -- a closed, stable list (the Länder do not get renamed),
 * unlike `group`, which is why this one is spelled out instead of read off the entry. */
const GERMAN_STATE_NAMES: Record<string, string> = {
  BW: 'Baden-Württemberg',
  BY: 'Bayern',
  BE: 'Berlin',
  BB: 'Brandenburg',
  HB: 'Bremen',
  HH: 'Hamburg',
  HE: 'Hessen',
  MV: 'Mecklenburg-Vorpommern',
  NI: 'Niedersachsen',
  NW: 'Nordrhein-Westfalen',
  RP: 'Rheinland-Pfalz',
  SL: 'Saarland',
  SN: 'Sachsen',
  ST: 'Sachsen-Anhalt',
  SH: 'Schleswig-Holstein',
  TH: 'Thüringen',
}

/**
 * The short hint a coverage limit earns in the picker (VERTRAG.md: "steuert nur den
 * Hinweis in der Auswahl, nichts Technisches"). `null` for `"world"` -- a basemap that
 * covers the whole world needs no caveat. Falls back to the raw code for a value this
 * table does not know (a coverage list can grow; a missing name must not hide the
 * caveat entirely).
 *
 * Not exported: only `BasemapEntryDetails` below renders this, and a file that exports
 * anything besides its components loses Fast Refresh (`oxlint --deny-warnings` fails
 * the build over it). Should a second caller ever need it, it moves to its own module
 * rather than becoming a second export here.
 */
function coverageHint(coverage: BasemapCoverage): string | null {
  if (coverage === 'world') return null
  if (coverage === 'DE') return 'Nur Deutschland'
  if (coverage === 'EU') return 'Nur EU'
  return `Nur ${GERMAN_STATE_NAMES[coverage] ?? coverage}`
}

/**
 * Title, hint and the three status notices (`requiresAccount`, `deprecated`,
 * `coverage`) for one catalog entry -- shared by every picker (`BasemapControl`,
 * `LayerBasemapDialog`, `CreateProjectDialog`) so the three fields the contract added
 * are shown the same way everywhere instead of being reinvented per picker.
 */
export function BasemapEntryDetails({ entry }: { entry: BasemapCatalogEntry }) {
  const coverage = coverageHint(entry.coverage)
  return (
    <span className="flex min-w-0 flex-col items-start gap-0.5 py-0.5">
      <span className="flex w-full min-w-0 items-center gap-1.5">
        <span className="truncate">{entry.title}</span>
        {entry.deprecated && (
          <Badge variant="secondary" className="shrink-0">
            Abgekündigt
          </Badge>
        )}
      </span>
      <span className="w-full truncate text-xs text-muted-foreground">{entry.hint}</span>
      {(entry.requiresAccount || coverage) && (
        <span className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs text-muted-foreground">
          {entry.requiresAccount && (
            <span className="flex items-center gap-1">
              <span className="size-1 shrink-0 rounded-full bg-muted-foreground" />
              ArcGIS-Konto erforderlich
            </span>
          )}
          {coverage && (
            <span className="flex items-center gap-1">
              <span className="size-1 shrink-0 rounded-full bg-muted-foreground" />
              {coverage}
            </span>
          )}
        </span>
      )}
    </span>
  )
}
