import { describe, expect, it } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { layerKeys, type LayerSummary } from './layers'
import { projectKeys } from './projects'
import {
  mergeFeaturesOptions,
  splitFeatureOptions,
  type MergeRequest,
  type MergeResponse,
  type SplitResponse,
  type SplitVariables,
} from './structure'

const LAYER = 'l1'
const OTHER_LAYER = 'l2'
const PROJECT = 'p1'

const SPLIT: SplitResponse = { fids: [42, 1001], dataVersion: 12, featureCount: 1338 }
const MERGE: MergeResponse = { fid: 42, dataVersion: 13, featureCount: 1336 }

const SPLIT_REQUEST: SplitVariables = {
  fid: 42,
  line: { type: 'LineString', coordinates: [] },
  rowVersion: '8241',
}
const MERGE_REQUEST: MergeRequest = { fids: [42, 43], leadFid: 42, rowVersions: {} }

/** Nur die Felder, die hier eine Rolle spielen -- der Rest der Zusammenfassung nicht. */
function summary(id: string, dataVersion: number, featureCount: number): LayerSummary {
  return { id, dataVersion, featureCount } as LayerSummary
}

/**
 * Ein QueryClient mit je einem Eintrag für alles, was ein Schreibvorgang berühren könnte.
 * Jeder wird als frisch geladen eingetragen, damit `isStale()` danach genau das eine
 * sagt: hat `onSuccess` diesen Eintrag für ungültig erklärt oder nicht.
 */
function seededClient() {
  const client = new QueryClient()
  client.setQueryData(layerKeys.list(PROJECT), [
    summary(LAYER, 11, 1339),
    summary(OTHER_LAYER, 4, 12),
  ])
  client.setQueryData(layerKeys.detail(LAYER), { id: LAYER, dataVersion: 11, featureCount: 1339 })
  client.setQueryData(['layers', LAYER, 'features', 'page'], {})
  client.setQueryData(['layers', LAYER, 'features', 42], {})
  client.setQueryData(projectKeys.list(''), {})
  client.setQueryData(projectKeys.detail(PROJECT), {})
  client.setQueryData(projectKeys.viewState(PROJECT), {})
  return client
}

function isStale(client: QueryClient, queryKey: readonly unknown[]): boolean {
  const query = client.getQueryCache().find({ queryKey, exact: true })
  if (!query) throw new Error(`Kein Eintrag für ${JSON.stringify(queryKey)}`)
  return query.isStale()
}

function runSplit(client: QueryClient, result = SPLIT) {
  const options = splitFeatureOptions(client, LAYER, PROJECT)
  void options.onSuccess?.(result, SPLIT_REQUEST, undefined, undefined as never)
}

function runMerge(client: QueryClient, result = MERGE) {
  const options = mergeFeaturesOptions(client, LAYER, PROJECT)
  void options.onSuccess?.(result, MERGE_REQUEST, undefined, undefined as never)
}

describe('splitFeatureOptions', () => {
  it('schreibt Objektzahl und dataVersion aus der Antwort in den Katalog', () => {
    const client = seededClient()

    runSplit(client)

    // CONTRACT.md 12.3: der Schreibvorgang zählt ohnehin neu und schickt das Ergebnis
    // mit. Den Katalog dafür erneut zu lesen wäre eine Anfrage nach einer Zahl, die
    // schon vorliegt.
    expect(client.getQueryData(layerKeys.list(PROJECT))).toEqual([
      summary(LAYER, 12, 1338),
      summary(OTHER_LAYER, 4, 12),
    ])
    expect(client.getQueryData(layerKeys.detail(LAYER))).toEqual({
      id: LAYER,
      dataVersion: 12,
      featureCount: 1338,
    })
  })

  it('fragt den Katalog danach nicht noch einmal ab', () => {
    const client = seededClient()

    runSplit(client)

    // Ohne das wäre die Antwort umsonst gekommen: der Eintrag würde sofort neu geladen,
    // und bis dahin stünde die alte Zahl im Layerbaum.
    expect(isStale(client, layerKeys.list(PROJECT))).toBe(false)
    expect(isStale(client, layerKeys.detail(LAYER))).toBe(false)
  })

  it('lädt die Objekte des Layers neu', () => {
    const client = seededClient()

    runSplit(client)

    // Die neuen Teile stehen in keiner zwischengespeicherten Seite, und das
    // ursprüngliche Objekt behält zwar seine fid, aber nicht seine Geometrie.
    expect(isStale(client, ['layers', LAYER, 'features', 'page'])).toBe(true)
    expect(isStale(client, ['layers', LAYER, 'features', 42])).toBe(true)
    // Objektanzahl und Ausdehnung des Projekts stehen in der Projektliste.
    expect(isStale(client, projectKeys.list(''))).toBe(true)
  })

  it('lässt Projektdetail und Arbeitsstand in Ruhe', () => {
    const client = seededClient()

    runSplit(client)

    // Wie bei `applyEditsOptions`: das Detail lädt mit `?open=true` nach und sortiert
    // damit die Projektliste um, der Arbeitsstand sitzt zwischen zwei eigenen,
    // zurückgestellten Schreibvorgängen.
    expect(isStale(client, projectKeys.detail(PROJECT))).toBe(false)
    expect(isStale(client, projectKeys.viewState(PROJECT))).toBe(false)
  })

  it('legt einen Katalog an, den niemand geladen hat, nicht an', () => {
    const client = new QueryClient()

    runSplit(client)

    expect(client.getQueryData(layerKeys.list(PROJECT))).toBeUndefined()
    expect(client.getQueryData(layerKeys.detail(LAYER))).toBeUndefined()
  })
})

describe('mergeFeaturesOptions', () => {
  it('schreibt die gesunkene Objektzahl aus der Antwort in den Katalog', () => {
    const client = seededClient()

    runMerge(client)

    expect(client.getQueryData(layerKeys.list(PROJECT))).toEqual([
      summary(LAYER, 13, 1336),
      summary(OTHER_LAYER, 4, 12),
    ])
    expect(isStale(client, layerKeys.list(PROJECT))).toBe(false)
  })

  it('lädt die Objekte des Layers neu', () => {
    const client = seededClient()

    runMerge(client)

    expect(isStale(client, ['layers', LAYER, 'features', 'page'])).toBe(true)
    expect(isStale(client, ['layers', LAYER, 'features', 42])).toBe(true)
    expect(isStale(client, projectKeys.list(''))).toBe(true)
  })

  it('lässt Projektdetail und Arbeitsstand in Ruhe', () => {
    const client = seededClient()

    runMerge(client)

    expect(isStale(client, projectKeys.detail(PROJECT))).toBe(false)
    expect(isStale(client, projectKeys.viewState(PROJECT))).toBe(false)
  })
})
