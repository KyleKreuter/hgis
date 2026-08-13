import { describe, expect, it } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { layerKeys } from './layers'
import { projectKeys } from './projects'
import {
  mergeFeaturesOptions,
  splitFeatureOptions,
  type MergeResponse,
  type SplitResponse,
} from './structure'

const LAYER = 'l1'
const PROJECT = 'p1'

const SPLIT: SplitResponse = { fids: [42, 1001], dataVersion: 12 }
const MERGE: MergeResponse = { fid: 42, dataVersion: 13 }

/**
 * Ein QueryClient mit je einem Eintrag für alles, was ein Schreibvorgang berühren könnte.
 * Jeder wird als frisch geladen eingetragen, damit `isStale()` danach genau das eine
 * sagt: hat `onSuccess` diesen Eintrag für ungültig erklärt oder nicht.
 */
function seededClient() {
  const client = new QueryClient()
  client.setQueryData(layerKeys.list(PROJECT), [])
  client.setQueryData(layerKeys.detail(LAYER), {})
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

describe('splitFeatureOptions', () => {
  it('lädt nach dem Teilen alles neu, was das Ergebnis zeigt', () => {
    const client = seededClient()

    const options = splitFeatureOptions(client, LAYER, PROJECT)
    void options.onSuccess?.(SPLIT, { fid: 42, line: { type: 'LineString', coordinates: [] }, rowVersion: '8241' }, undefined, undefined as never)

    // The new parts exist in no cached page yet, and the tile URL is built from the
    // layer list's `dataVersion` -- without that one the map keeps showing the old
    // outline of an object that no longer has it.
    expect(isStale(client, layerKeys.list(PROJECT))).toBe(true)
    expect(isStale(client, layerKeys.detail(LAYER))).toBe(true)
    expect(isStale(client, ['layers', LAYER, 'features', 'page'])).toBe(true)
    // The original survives the split and keeps its fid, but not its geometry.
    expect(isStale(client, ['layers', LAYER, 'features', 42])).toBe(true)
    expect(isStale(client, projectKeys.list(''))).toBe(true)
  })

  it('lässt Projektdetail und Arbeitsstand in Ruhe', () => {
    const client = seededClient()

    const options = splitFeatureOptions(client, LAYER, PROJECT)
    void options.onSuccess?.(SPLIT, { fid: 42, line: { type: 'LineString', coordinates: [] }, rowVersion: '8241' }, undefined, undefined as never)

    // Same reasoning as `applyEditsOptions`: the detail refetches with `?open=true` and
    // would reorder the project list, and the working state sits between two of its own
    // deferred writes.
    expect(isStale(client, projectKeys.detail(PROJECT))).toBe(false)
    expect(isStale(client, projectKeys.viewState(PROJECT))).toBe(false)
  })
})

describe('mergeFeaturesOptions', () => {
  it('lädt nach dem Zusammenführen alles neu, was das Ergebnis zeigt', () => {
    const client = seededClient()

    const options = mergeFeaturesOptions(client, LAYER, PROJECT)
    void options.onSuccess?.(MERGE, { fids: [42, 43], leadFid: 42, rowVersions: {} }, undefined, undefined as never)

    expect(isStale(client, layerKeys.list(PROJECT))).toBe(true)
    expect(isStale(client, layerKeys.detail(LAYER))).toBe(true)
    expect(isStale(client, ['layers', LAYER, 'features', 'page'])).toBe(true)
    expect(isStale(client, ['layers', LAYER, 'features', 42])).toBe(true)
    expect(isStale(client, projectKeys.list(''))).toBe(true)
  })

  it('lässt Projektdetail und Arbeitsstand in Ruhe', () => {
    const client = seededClient()

    const options = mergeFeaturesOptions(client, LAYER, PROJECT)
    void options.onSuccess?.(MERGE, { fids: [42, 43], leadFid: 42, rowVersions: {} }, undefined, undefined as never)

    expect(isStale(client, projectKeys.detail(PROJECT))).toBe(false)
    expect(isStale(client, projectKeys.viewState(PROJECT))).toBe(false)
  })
})
