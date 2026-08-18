import { describe, expect, it } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { applyEditsOptions, applyFeatureWriteResult, type EditResponse } from './edits'
import { layerKeys } from './layers'
import { projectKeys } from './projects'

const LAYER = 'l1'
const PROJECT = 'p1'

const RESPONSE: EditResponse = {
  createdFids: {},
  updated: 1,
  deleted: 0,
  dataVersion: 8,
  featureCount: 42,
}

/**
 * Ein QueryClient mit je einem Eintrag für alles, was ein Edit berühren könnte. Jeder
 * wird als frisch geladen eingetragen, damit `isStale()` danach genau das eine sagt:
 * hat `onSuccess` diesen Eintrag für ungültig erklärt oder nicht.
 */
function seededClient() {
  const client = new QueryClient()
  client.setQueryData(layerKeys.list(PROJECT), [])
  client.setQueryData(layerKeys.detail(LAYER), {})
  client.setQueryData(['layers', LAYER, 'features', 'page'], {})
  client.setQueryData(layerKeys.classify(LAYER, 'waermebedarf', 'quantile', 12), {})
  client.setQueryData(projectKeys.list(''), {})
  client.setQueryData(projectKeys.list('such'), {})
  client.setQueryData(projectKeys.detail(PROJECT), {})
  client.setQueryData(projectKeys.viewState(PROJECT), {})
  return client
}

function isStale(client: QueryClient, queryKey: readonly unknown[]): boolean {
  const query = client.getQueryCache().find({ queryKey, exact: true })
  if (!query) throw new Error(`Kein Eintrag für ${JSON.stringify(queryKey)}`)
  return query.isStale()
}

function runOnSuccess(client: QueryClient) {
  const options = applyEditsOptions(client, LAYER, PROJECT)
  // `invalidateQueries` markiert synchron und liefert danach erst ein Promise fürs
  // Nachladen -- für die Frage "ist der Eintrag ungültig" genügt der Aufruf.
  void options.onSuccess?.(RESPONSE, {}, undefined, undefined as never)
}

describe('applyEditsOptions', () => {
  it('erklärt Layerliste, Layerdetail und Objektseiten für ungültig', () => {
    const client = seededClient()

    runOnSuccess(client)

    // Ohne die Layerliste erscheint die Änderung gar nicht erst auf der Karte: aus ihr
    // wird die Kachel-URL mit der neuen dataVersion gebaut.
    expect(isStale(client, layerKeys.list(PROJECT))).toBe(true)
    expect(isStale(client, layerKeys.detail(LAYER))).toBe(true)
    expect(isStale(client, ['layers', LAYER, 'features', 'page'])).toBe(true)
  })

  /**
   * Team review, package 2 (Prüfer): a write can move a field's min or max, and this was
   * the one write path with no invalidation of its own for `heatmapFieldRangeQuery`'s
   * cache -- own-session edits used to be invisible to a heatmap's weight normalisation
   * and to its legend's "did the data outgrow this fixed bound" check for up to the
   * query's own five-minute `staleTime`.
   *
   * Not this test's own proof on its own, though: `invalidateAfterFeatureWrite` also
   * invalidates `layerKeys.detail(LAYER)` (`['layers', LAYER]`), which as a prefix already
   * covers `['layers', LAYER, 'classify', ...]` under TanStack Query's default partial-key
   * matching -- this assertion would stay green even without `invalidateFeatureData`'s own
   * classify line (mutation testing, package 2: deleting that line left this test passing).
   * It stays anyway, as the actually-taken path's documented behaviour; the split/merge
   * test right below is the one that isolates the line itself, on the one path that never
   * invalidates `layerKeys.detail` at all.
   */
  it('erklärt die Feldspanne (heatmapFieldRangeQuery) für ungültig', () => {
    const client = seededClient()

    runOnSuccess(client)

    expect(isStale(client, layerKeys.classify(LAYER, 'waermebedarf', 'quantile', 12))).toBe(true)
  })

  /**
   * `applyFeatureWriteResult` (split/merge, section 12) writes `layerKeys.detail` with
   * `setQueryData` -- an optimistic update, not an invalidation -- so unlike the ordinary
   * edit path above, nothing here marks the classify cache stale by an unrelated prefix
   * match. Whatever staleness shows up here can only have come from
   * `invalidateFeatureData`'s own classify invalidation.
   */
  it('erklärt die Feldspanne auch beim Teilen/Zusammenführen für ungültig', () => {
    const client = seededClient()

    applyFeatureWriteResult(client, LAYER, PROJECT, { dataVersion: 9, featureCount: 43 })

    expect(isStale(client, layerKeys.classify(LAYER, 'waermebedarf', 'quantile', 12))).toBe(true)
  })

  it('erklärt jede Seitenkette der Projektliste für ungültig', () => {
    const client = seededClient()

    runOnSuccess(client)

    // Objektanzahl und Ausdehnung des Projekts stehen in der Liste, und zwar in jeder
    // Kette -- es gibt eine je Suchbegriff.
    expect(isStale(client, projectKeys.list(''))).toBe(true)
    expect(isStale(client, projectKeys.list('such'))).toBe(true)
  })

  it('lässt Projektdetail und Arbeitsstand in Ruhe', () => {
    const client = seededClient()

    runOnSuccess(client)

    // Der belegte Fehler: `projectKeys.all` ist Präfix von beiden. Das Detail wurde mit
    // `?open=true` nachgeladen (setzt `lastOpenedAt` neu und sortiert die Projektliste
    // um), ein optimistischer `basemap`-Wert konnte zurückfallen, und der Arbeitsstand
    // wurde mitten zwischen zwei zurückgestellten Schreibvorgängen neu geladen.
    expect(isStale(client, projectKeys.detail(PROJECT))).toBe(false)
    expect(isStale(client, projectKeys.viewState(PROJECT))).toBe(false)
  })
})
