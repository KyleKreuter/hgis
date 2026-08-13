import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test } from 'vitest'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { GeoportalCatalog, GeoportalDatasetDetail } from '@/api/geoportal'
import { GeoportalDialog } from './GeoportalDialog'

/**
 * The dialog's detail pane, driven through the real query hooks with only `fetch`
 * stubbed.
 *
 * The one rule under test is the description's source: the service directory carries no
 * description for any catalog entry, so the pane must show the one the detail call
 * fetched from the API landing page and fall back to the list entry's only when the
 * detail has none. A test on the merge helper alone would not have caught the pane
 * reading `summary.description` -- the bug is in which of the two values reaches the
 * screen, so the screen is what this asserts on.
 */

const CATALOG: GeoportalCatalog = {
  fetchedAt: '2026-01-01T00:00:00Z',
  datasets: [
    {
      id: 'ds-1',
      title: 'Baumkataster',
      description: 'Beschreibung aus dem Listeneintrag',
      kind: 'FEATURES',
      agency: 'Landesbetrieb Geoinformation und Vermessung',
      topic: 'Umwelt',
      featureCount: 100,
      bbox: null,
    },
  ],
}

function detailWith(description: string | null): GeoportalDatasetDetail {
  return {
    ...CATALOG.datasets[0],
    description,
    attribution: 'Freie und Hansestadt Hamburg, LGV',
    licenseName: 'Datenlizenz Deutschland Namensnennung 2.0',
    licenseUrl: 'https://www.govdata.de/dl-de/by-2-0',
    datasetUri: null,
    metadataUrl: null,
    storageSrid: 25832,
    sourceFeatureIdField: null,
    fields: [{ name: 'baumart', title: 'Baumart', dataType: 'text', values: [] }],
  }
}

function renderDialog(detail: GeoportalDatasetDetail) {
  stubFetch([
    { match: '/api/geoportal/datasets/ds-1', body: detail },
    { match: '/api/geoportal/datasets', body: CATALOG },
  ])
  return renderWithQueryClient(
    <GeoportalDialog projectId="p-1" open onOpenChange={() => {}} />,
  )
}

async function selectBaumkataster() {
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: /Baumkataster/ }))
}

describe('GeoportalDialog detail pane', () => {
  test('shows the description the detail call fetched, not the list entry"s', async () => {
    renderDialog(detailWith('Beschreibung aus dem Detail'))
    await selectBaumkataster()

    expect(await screen.findByText('Beschreibung aus dem Detail')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByText('Beschreibung aus dem Listeneintrag')).not.toBeInTheDocument(),
    )
  })

  test('falls back to the list entry when the detail carries no description', async () => {
    renderDialog(detailWith(null))
    await selectBaumkataster()

    // Waiting for a field only the detail carries proves the fallback is what is on
    // screen afterwards, rather than the pane still showing its pre-detail state.
    expect(await screen.findByText(/Freie und Hansestadt Hamburg/)).toBeInTheDocument()
    expect(screen.getByText('Beschreibung aus dem Listeneintrag')).toBeInTheDocument()
  })
})
