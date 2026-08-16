import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { useSelection } from '@/state/selection'
import { renderWithQueryClient, stubFetch, testQueryClient } from '@/test/render'
import type { LayerDetail } from '@/api/layers'
import type { FeaturePage } from '@/api/features'
import { EMPTY_VIEW_STATE, type ViewStateDocument } from '@/state/viewState'
import type { ViewStateWriter } from '@/state/useViewState'
import { AttributeTable } from './AttributeTable'

/**
 * Sort and search on a layer switch, asserted on the request URL rather than on the
 * component's state.
 *
 * The URL is what decides what the user sees: sort and search reach the server as query
 * parameters, so a sort left over from the previous layer shows up there and nowhere
 * else. Reading it also keeps the test clear of `useState`, which the restore path
 * around it is being rewritten in.
 */

function layerDetail(id: string, name: string): LayerDetail {
  return {
    id,
    name,
    geometryType: 'MULTIPOLYGON',
    srid: 25832,
    featureCount: 2,
    visible: true,
    zIndex: 0,
    minZoom: 0,
    maxZoom: 22,
    dataVersion: 1,
    styleVersion: 1,
    extent: null,
    fields: [
      { id: 'f-1', sourceName: 'Name', columnName: 'name', dataType: 'text' },
      { id: 'f-2', sourceName: 'Baujahr', columnName: 'baujahr', dataType: 'integer' },
    ],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

const PAGE: FeaturePage = {
  features: [
    { fid: 1, rowVersion: '1', properties: { name: 'Alpha', baujahr: 1900 } },
    { fid: 2, rowVersion: '1', properties: { name: 'Beta', baujahr: 1950 } },
  ],
  totalCount: 2,
}

/** A writer that records instead of persisting -- the table only needs it to accept calls. */
function fakeViewState(document: ViewStateDocument = EMPTY_VIEW_STATE): ViewStateWriter {
  return {
    document,
    ready: true,
    writeActiveLayer: vi.fn(),
    writeSort: vi.fn(),
    writeQuery: vi.fn(),
    writeSelection: vi.fn(() => true),
    hasPendingWrite: () => false,
  }
}

/** A saved working state for layer-a, sorted by whatever `field` names. */
function savedSort(field: string, desc = false): ViewStateDocument {
  return {
    version: 1,
    activeLayerId: 'layer-a',
    layers: { 'layer-a': { sort: { field, desc }, query: null, selection: [] } },
  }
}

function setup(document: ViewStateDocument = EMPTY_VIEW_STATE) {
  const { calls } = stubFetch([
    { match: '/features', body: PAGE },
    { match: '/api/layers/layer-a', body: layerDetail('layer-a', 'Gebäude') },
    { match: '/api/layers/layer-b', body: layerDetail('layer-b', 'Straßen') },
  ])
  const client = testQueryClient()
  const props = {
    projectId: 'p-1',
    viewState: fakeViewState(document),
    onZoomToFeature: vi.fn(),
    onRequestEdit: vi.fn(),
  }
  const view = renderWithQueryClient(
    <AttributeTable layerId="layer-a" layerName="Gebäude" layerFeatureCount={2} {...props} />,
    client,
  )
  const switchToLayerB = () =>
    view.rerender(
      <AttributeTable layerId="layer-b" layerName="Straßen" layerFeatureCount={2} {...props} />,
    )
  return { calls, switchToLayerB, user: userEvent.setup() }
}

/** Feature requests only -- the layer detail calls share the prefix but carry no query. */
function featureCalls(calls: string[], layerId: string): string[] {
  return calls.filter((url) => url.includes(`/api/layers/${layerId}/features`))
}

/**
 * Waits until the newest request for `layerId` is free of `parameter`.
 *
 * The newest one, not every one: a switch may legitimately render once before an effect
 * settles the new layer's state, and that first request is not what the user ends up
 * looking at. What must hold is that the table comes to rest on an unrestricted query.
 */
async function waitForLastCallWithout(calls: string[], layerId: string, parameter: string) {
  await waitFor(
    () => {
      const last = featureCalls(calls, layerId).at(-1)
      expect(last).toBeDefined()
      expect(last).not.toContain(parameter)
    },
    { timeout: 2000 },
  )
}

/** Whether a column header currently carries its sort arrow. */
function hasSortArrow(header: HTMLElement): boolean {
  return header.querySelector('svg') !== null
}

describe('AttributeTable', () => {
  /**
   * The field id, not the column name. A column name is only unique among columns: a layer
   * can carry a field whose display name is another field's column name, and the server
   * refuses such a name rather than guessing. Clicking that header did nothing at all --
   * 400, silent fallback to unsorted, no arrow and no message.
   */
  test('sends sort and search to the server as the user sets them', async () => {
    const { calls, user } = setup()

    await user.click(await screen.findByRole('button', { name: 'Baujahr' }))
    await waitFor(() =>
      expect(featureCalls(calls, 'layer-a').some((url) => url.includes('sort=f-2'))).toBe(true),
    )

    await user.type(await screen.findByRole('textbox'), 'Alpha')
    await waitFor(
      () =>
        expect(featureCalls(calls, 'layer-a').some((url) => url.includes('search=Alpha'))).toBe(
          true,
        ),
      { timeout: 2000 },
    )
  })

  /*
   * These two were written as `test.fails` while the component still carried a sort or
   * search from one layer to the next; they turned green the moment that was fixed, and
   * are plain tests since.
   *
   * They deliberately assert nothing but the rule. The setup they share with the test
   * above -- that a sort and a search reach the server at all -- is covered there, in a
   * plain test, so neither of these can pass merely because its setup broke.
   */

  /**
   * A state saved before the switch to ids holds a column name, and the server still
   * serves it. The arrow has to follow: comparing against the id alone would leave such a
   * table sorted correctly and looking unsorted -- a false negative rather than a false
   * positive, and just as misleading.
   */
  test('shows the arrow for a sort saved as a column name', async () => {
    const { calls } = setup(savedSort('baujahr', true))

    await waitFor(() =>
      expect(featureCalls(calls, 'layer-a').some((url) => url.includes('sort=baujahr'))).toBe(true),
    )
    const header = await screen.findByRole('button', { name: 'Baujahr' })
    await waitFor(() => expect(hasSortArrow(header)).toBe(true))
  })

  test('shows the arrow for a sort saved as a field id', async () => {
    const { calls } = setup(savedSort('f-2'))

    await waitFor(() =>
      expect(featureCalls(calls, 'layer-a').some((url) => url.includes('sort=f-2'))).toBe(true),
    )
    const header = await screen.findByRole('button', { name: 'Baujahr' })
    await waitFor(() => expect(hasSortArrow(header)).toBe(true))
  })

  /** And the negative case, so the two above cannot pass on an arrow that is always there. */
  test('shows no arrow on a column that is not the sort field', async () => {
    setup(savedSort('f-2'))

    const other = await screen.findByRole('button', { name: 'Name' })
    await waitFor(() => expect(hasSortArrow(other)).toBe(false))
  })

  test('drops the previous layer"s sort on a layer switch', async () => {
    const { calls, switchToLayerB, user } = setup()

    await user.click(await screen.findByRole('button', { name: 'Baujahr' }))
    await waitFor(() =>
      expect(featureCalls(calls, 'layer-a').some((url) => url.includes('sort=f-2'))).toBe(true),
    )

    switchToLayerB()
    // Layer B saved no sort of its own, so it must end up unsorted. `baujahr` exists on
    // both fixtures, so a leftover sort does not fail loudly -- it silently reorders a
    // layer the user never sorted.
    await waitForLastCallWithout(calls, 'layer-b', 'sort=')
  })

  test('drops the previous layer"s search term on a layer switch', async () => {
    const { calls, switchToLayerB, user } = setup()

    await user.type(await screen.findByRole('textbox'), 'Alpha')
    await waitFor(
      () =>
        expect(featureCalls(calls, 'layer-a').some((url) => url.includes('search=Alpha'))).toBe(
          true,
        ),
      { timeout: 2000 },
    )

    switchToLayerB()
    await waitForLastCallWithout(calls, 'layer-b', 'search=')
    // The bar has to agree with what was sent: a cleared query behind a field still
    // reading "Alpha" tells the user their search is active when it is not.
    expect(await screen.findByRole('textbox')).toHaveValue('')
  })

  /**
   * A Kartenbild has no fields and no features (plan Stufe 4). `stubFetch` with an empty
   * route list is the actual assertion here: any call the component made that this test
   * does not expect would reject and fail it, so a plain "the sentence shows up" render
   * would not have caught a lingering feature or field-detail request.
   */
  test('zeigt für ein Kartenbild einen Satz statt einer leeren Tabelle und fragt keine Objekte ab', async () => {
    const { calls } = stubFetch([])
    renderWithQueryClient(
      <AttributeTable
        layerId="img-1"
        layerName="Stadtplan"
        layerKind="WMS"
        projectId="p-1"
        viewState={fakeViewState()}
        onZoomToFeature={vi.fn()}
        onRequestEdit={vi.fn()}
      />,
    )

    expect(await screen.findByText('Ein Kartenbild hat keine Attribute.')).toBeInTheDocument()
    expect(screen.getByText('Attribute - Stadtplan')).toBeInTheDocument()
    expect(calls).toEqual([])
  })

  /**
   * The two halves of one rule: a selection the user makes is saved, a selection that
   * came from elsewhere is not.
   *
   * They belong together. On its own, the negative one would also pass if the table
   * stopped saving selections altogether, or if the subscription never fired -- which is
   * exactly the state the rule is meant to be distinguished from.
   */
  describe('Auswahl speichern', () => {
    function renderWithSelection(selection: number[]) {
      stubFetch([
        // Before '/features': `stubFetch` matches by substring and takes the first hit,
        // and the fids URL contains that prefix.
        { match: '/features/fids', body: { fids: [1, 2] } },
        { match: '/features', body: PAGE },
        { match: '/api/layers/layer-a', body: layerDetail('layer-a', 'Gebäude') },
      ])
      useSelection.setState({ layerId: null, selected: new Set() })
      const viewState = fakeViewState({
        version: 1,
        activeLayerId: 'layer-a',
        layers: { 'layer-a': { sort: null, query: null, selection } },
      })
      renderWithQueryClient(
        <AttributeTable
          layerId="layer-a"
          layerName="Gebäude"
          layerFeatureCount={2}
          projectId="p-1"
          viewState={viewState}
          onZoomToFeature={vi.fn()}
          onRequestEdit={vi.fn()}
        />,
      )
      return viewState
    }

    test('speichert eine Auswahl, die der Nutzer trifft', async () => {
      const viewState = renderWithSelection([])
      // Der Spaltenkopf steht erst, wenn der Layer geladen ist -- danach hängt auch die
      // Beobachtung der Auswahl. (Zeilen prüft dieser Test nicht: jsdom rechnet kein
      // Layout, und die virtualisierte Liste zeigt ohne Höhe gar keine.)
      await screen.findByRole('button', { name: 'Baujahr' })

      act(() => useSelection.getState().select('layer-a', [2]))

      expect(viewState.writeSelection).toHaveBeenCalledWith('layer-a', [2])
    })

    test('speichert eine wiederhergestellte Auswahl nicht erneut', async () => {
      const viewState = renderWithSelection([1])

      await waitFor(() => expect(useSelection.getState().selected).toEqual(new Set([1])))

      // Der Wert kam gerade vom Server. Ihn zurückzuschreiben wäre die erste Runde einer
      // Schleife -- und über den Live-Kanal eine Antwort auf die Änderung eines anderen.
      expect(viewState.writeSelection).not.toHaveBeenCalled()
    })
  })
})
