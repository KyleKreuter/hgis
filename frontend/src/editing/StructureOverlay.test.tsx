import { QueryClient } from '@tanstack/react-query'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, test } from 'vitest'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { Feature } from '@/api/features'
import type { LayerField } from '@/api/layers'
import { layerKeys } from '@/api/layers'
import { useSelection } from '@/state/selection'
import { StructureOverlay } from './StructureOverlay'
import { useStructure } from './structureStore'

/**
 * What happens after a structural write goes through: the view reloads, and the result
 * is what is selected.
 *
 * Only the merge phase is exercised here. The split phase mounts `SplitLineTool`, which
 * needs a real `useMap()` and therefore a MapLibre canvas -- its own decisions are
 * checked in `splitLine.test.ts` and `structureStore.test.ts` instead.
 */

const FIELDS: LayerField[] = [
  { id: 'f1', sourceName: 'Straße', columnName: 'strasse', dataType: 'text' },
]

function feature(fid: number, rowVersion: string, strasse: string): Feature {
  return { fid, rowVersion, properties: { strasse }, geometry: { type: 'Polygon', coordinates: [] } }
}

describe('StructureOverlay', () => {
  beforeEach(() => {
    useSelection.getState().clear()
    useStructure.getState().cancel()
  })

  test('zeigt ohne laufendes Werkzeug nichts an', () => {
    stubFetch([])
    renderWithQueryClient(<StructureOverlay layerId="l1" projectId="p1" fields={FIELDS} />)

    expect(screen.queryByRole('dialog')).toBeNull()
  })

  test('wählt nach dem Zusammenführen das übrig gebliebene Objekt aus und schreibt die neue Zahl fort', async () => {
    stubFetch([
      { match: '/features/merge', body: { fid: 42, dataVersion: 13, featureCount: 1336 } },
      { match: '/features/42', body: feature(42, '8241', 'Alte Landstraße') },
      { match: '/features/43', body: feature(43, '8242', 'Neue Landstraße') },
    ])
    useSelection.getState().select('l1', [42, 43])
    useStructure.getState().openMerge()

    // Not `testQueryClient()`: its `gcTime: 0` drops an entry nothing observes the
    // moment it is written, and the seeded layer list below has no observer here.
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    // The layer catalog as the workspace holds it, with the counts from before the merge.
    client.setQueryData(layerKeys.list('p1'), [{ id: 'l1', dataVersion: 12, featureCount: 1337 }])
    renderWithQueryClient(<StructureOverlay layerId="l1" projectId="p1" fields={FIELDS} />, client)

    await screen.findByText('Alte Landstraße')
    await userEvent.click(screen.getByRole('radio', { name: 'Objekt 42 führt' }))
    await userEvent.click(screen.getByRole('button', { name: 'Zusammenführen' }))

    await waitFor(() => expect(useStructure.getState().phase).toEqual({ type: 'idle' }))
    // Object 43 no longer exists. A selection still holding it would highlight nothing
    // and would offer a second merge on a row that is gone.
    expect([...useSelection.getState().selected]).toEqual([42])
    // Straight from the response, not from a second read of the catalog (CONTRACT.md
    // 12.3). The tile URL is built from `dataVersion`, so the map redraws in the same
    // frame -- and the layer tree never shows the old count in between.
    expect(client.getQueryData(layerKeys.list('p1'))).toEqual([
      { id: 'l1', dataVersion: 13, featureCount: 1336 },
    ])
  })
})
