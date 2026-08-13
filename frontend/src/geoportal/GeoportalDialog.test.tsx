import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test } from 'vitest'
import { renderWithQueryClient, stubElementSize, stubFetch } from '@/test/render'
import type {
  GeoportalCatalog,
  GeoportalDatasetDetail,
  GeoportalDatasetSummary,
} from '@/api/geoportal'
import { GeoportalDialog } from './GeoportalDialog'

/**
 * The dialog's detail pane, driven through the real query hooks with only `fetch`
 * stubbed.
 *
 * Two rules are under test here. The description's source: the service directory carries
 * no description for any catalog entry, so the pane must show the one the detail call
 * fetched from the API landing page and fall back to the list entry's only when the
 * detail has none. And the collection choice a service entry owes (CONTRACT.md 11.9),
 * which spans list, detail call and import body -- a test on any one helper alone would
 * not have caught the id the import actually names.
 */

const FLAT: GeoportalDatasetSummary = {
  id: 'ds-1',
  title: 'Baumkataster',
  description: 'Beschreibung aus dem Listeneintrag',
  kind: 'FEATURES',
  agency: 'Landesbetrieb Geoinformation und Vermessung',
  topic: 'Umwelt',
  featureCount: 100,
  bbox: null,
  collectionCount: 1,
}

/** A service listed as one row: no collection named, so nothing to import from yet. */
const SERVICE: GeoportalDatasetSummary = {
  id: 'xplan',
  title: 'XPlanung',
  description: null,
  kind: 'FEATURES',
  agency: 'Landesbetrieb Geoinformation und Vermessung',
  topic: 'Planung',
  featureCount: null,
  bbox: null,
  collectionCount: 247,
}

const CATALOG: GeoportalCatalog = {
  fetchedAt: '2026-01-01T00:00:00Z',
  datasets: [FLAT, SERVICE],
}

const LICENCE = {
  attribution: 'Freie und Hansestadt Hamburg, LGV',
  licenseName: 'Datenlizenz Deutschland Namensnennung 2.0',
  licenseUrl: 'https://www.govdata.de/dl-de/by-2-0',
  datasetUri: null,
  metadataUrl: null,
  storageSrid: 25832,
}

function detailWith(description: string | null): GeoportalDatasetDetail {
  return {
    ...FLAT,
    ...LICENCE,
    description,
    sourceFeatureIdField: null,
    fields: [{ name: 'baumart', title: 'Baumart', dataType: 'text', values: [] }],
    collections: [],
  }
}

/** The service's own detail: collections instead of fields, per 11.9. */
const SERVICE_DETAIL: GeoportalDatasetDetail = {
  ...SERVICE,
  ...LICENCE,
  sourceFeatureIdField: null,
  fields: [],
  collections: [
    { id: 'xplan/so_plan', title: 'Sonstiger Plan' },
    { id: 'xplan/bp_plan', title: 'Bebauungsplan' },
    { id: 'xplan/fp_plan', title: 'Flächennutzungsplan' },
  ],
}

const COLLECTION_DETAIL: GeoportalDatasetDetail = {
  ...SERVICE_DETAIL,
  id: 'xplan/bp_plan',
  title: 'Bebauungsplan',
  featureCount: 4200,
  sourceFeatureIdField: 'gid',
  fields: [{ name: 'plannummer', title: 'Plannummer', dataType: 'text', values: [] }],
  // Deliberately the service's count, not 1: the contract does not say which of the two
  // a collection's detail carries, so the dialog must decide from the catalog entry it
  // holds and never from this field. If it read this one, the pane would ask for a
  // collection again after one was chosen and the import would stay unreachable.
  collectionCount: 247,
  collections: [],
}

const JOB = {
  id: 'job-1',
  type: 'IMPORT',
  status: 'RUNNING',
  processedCount: 0,
  skippedCount: 0,
  totalCount: 4200,
  filename: null,
  message: null,
}

/** Longest match first: every detail url contains the catalog's url as a prefix. */
function renderDialog(flatDetail: GeoportalDatasetDetail = detailWith(null)) {
  // The catalog list is virtualised, so without a height no row exists to click.
  stubElementSize()
  const stub = stubFetch([
    { match: '/geoportal-imports', body: JOB, status: 202 },
    { match: '/api/geoportal/datasets/xplan/bp_plan', body: COLLECTION_DETAIL },
    { match: '/api/geoportal/datasets/xplan', body: SERVICE_DETAIL },
    { match: '/api/geoportal/datasets/ds-1', body: flatDetail },
    { match: '/api/geoportal/datasets', body: CATALOG },
  ])
  return {
    ...stub,
    ...renderWithQueryClient(<GeoportalDialog projectId="p-1" open onOpenChange={() => {}} />),
  }
}

async function clickEntry(name: RegExp) {
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name }))
  return user
}

describe('GeoportalDialog detail pane', () => {
  test('shows the description the detail call fetched, not the list entry"s', async () => {
    renderDialog(detailWith('Beschreibung aus dem Detail'))
    await clickEntry(/Baumkataster/)

    expect(await screen.findByText('Beschreibung aus dem Detail')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByText('Beschreibung aus dem Listeneintrag')).not.toBeInTheDocument(),
    )
  })

  test('falls back to the list entry when the detail carries no description', async () => {
    renderDialog(detailWith(null))
    await clickEntry(/Baumkataster/)

    // Waiting for a field only the detail carries proves the fallback is what is on
    // screen afterwards, rather than the pane still showing its pre-detail state.
    expect(await screen.findByText(/Freie und Hansestadt Hamburg/)).toBeInTheDocument()
    expect(screen.getByText('Beschreibung aus dem Listeneintrag')).toBeInTheDocument()
  })
})

describe('GeoportalDialog collection choice', () => {
  test('marks a service entry in the list with its collection count', async () => {
    renderDialog()
    expect(await screen.findByText('247 Sammlungen')).toBeInTheDocument()
  })

  test('asks for a collection instead of offering fields and an import', async () => {
    renderDialog()
    await clickEntry(/XPlanung/)

    expect(await screen.findByRole('button', { name: 'Bebauungsplan' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Layername')).not.toBeInTheDocument()
    expect(screen.queryByText('Felder')).not.toBeInTheDocument()
    // "Anzahl unbekannt" would claim the number is missing; none is due yet.
    expect(screen.queryByText(/Anzahl unbekannt/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Importieren' })).toBeDisabled()
  })

  test('narrows the collection list to the search', async () => {
    renderDialog()
    const user = await clickEntry(/XPlanung/)

    const list = await screen.findByRole('list', { name: 'Sammlungen des Dienstes' })
    await user.type(screen.getByLabelText('Sammlungen durchsuchen'), 'flächen')

    await waitFor(() =>
      expect(within(list).queryByRole('button', { name: 'Bebauungsplan' })).not.toBeInTheDocument(),
    )
    expect(within(list).getByRole('button', { name: 'Flächennutzungsplan' })).toBeInTheDocument()
  })

  test('shows the chosen collection"s own fields and count, and only then imports it', async () => {
    const { requests } = renderDialog()
    const user = await clickEntry(/XPlanung/)
    await user.click(await screen.findByRole('button', { name: 'Bebauungsplan' }))

    expect(await screen.findByText('Plannummer')).toBeInTheDocument()
    expect(screen.getByText(/4\.200 Objekte/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Importieren' }))

    await waitFor(() => {
      const started = requests.find((request) => request.url.includes('/geoportal-imports'))
      // The collection, never the service: a service id alone is a 400 (11.9).
      expect(JSON.parse(String(started?.init?.body))).toMatchObject({ datasetId: 'xplan/bp_plan' })
    })
  })

  test('lets the user go back to the collection list', async () => {
    renderDialog()
    const user = await clickEntry(/XPlanung/)
    await user.click(await screen.findByRole('button', { name: 'Bebauungsplan' }))
    await screen.findByText('Plannummer')

    await user.click(screen.getByRole('button', { name: 'Andere Sammlung' }))

    expect(
      await screen.findByRole('button', { name: 'Flächennutzungsplan' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Importieren' })).toBeDisabled()
  })

  test('drops the choice when another catalog entry is selected', async () => {
    renderDialog()
    const user = await clickEntry(/XPlanung/)
    await user.click(await screen.findByRole('button', { name: 'Bebauungsplan' }))
    await screen.findByText('Plannummer')

    await user.click(screen.getByRole('button', { name: /Baumkataster/ }))
    expect(await screen.findByText('Baumart')).toBeInTheDocument()

    // Back at the service, the choice must be gone -- otherwise the previous
    // collection's fields would describe an entry the user has not chosen it for.
    await user.click(screen.getByRole('button', { name: /XPlanung/ }))
    expect(await screen.findByRole('button', { name: 'Bebauungsplan' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Importieren' })).toBeDisabled()
  })
})
