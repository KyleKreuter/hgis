import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { WmsCapabilities } from '@/api/wms'
import { MapImageSection } from './MapImageSection'

const CAPABILITIES: WmsCapabilities = {
  serviceUrl: 'https://geodienste.hamburg.de/HH_WMS_Geobasiskarten',
  title: 'WMS Geobasiskarten Hamburg (farbig)',
  version: '1.3.0',
  imageFormats: ['image/png', 'image/jpeg'],
  layers: [
    {
      name: 'geobasiskarten_farbig',
      title: 'Geobasiskarten (farbig)',
      depth: 0,
      queryable: true,
      legendUrl: null,
      minScale: null,
      maxScale: null,
      bbox: null,
    },
    {
      name: 'm2500_farbig',
      title: 'M2500 (farbig)',
      depth: 1,
      queryable: false,
      legendUrl: null,
      minScale: null,
      maxScale: 3000,
      bbox: null,
    },
  ],
}

/**
 * Driven through the real `useWmsCapabilities`/`useCreateMapImageLayer` hooks with only
 * `fetch` stubbed -- the same rule the Geoportal dialog's own tests follow, so this fails
 * the moment the component stops asking for what the contract actually promises.
 */
describe('MapImageSection', () => {
  test('listet die Layer flach mit Einrückung und zeigt, was der Dienst dazu sagt', async () => {
    stubFetch([{ match: '/api/wms/capabilities', body: CAPABILITIES }])
    renderWithQueryClient(
      <MapImageSection projectId="p-1" wmsUrl="https://geodienste.hamburg.de/HH_WMS_Geobasiskarten" onAdded={vi.fn()} />,
    )

    expect(await screen.findByText('Geobasiskarten (farbig)')).toBeInTheDocument()
    expect(screen.getByText('M2500 (farbig)')).toBeInTheDocument()
    expect(screen.getByText('nicht abfragbar')).toBeInTheDocument()
    expect(screen.getByText('bis 1:3.000')).toBeInTheDocument()
  })

  test('legt das Kartenbild mit den gewählten Layern in der Dienstreihenfolge an', async () => {
    const { requests } = stubFetch([
      { match: '/api/wms/capabilities', body: CAPABILITIES },
      { match: '/api/projects/p-1/map-layers', body: { id: 'img-1', name: 'Geobasiskarten (farbig)' } },
    ])
    const onAdded = vi.fn()
    renderWithQueryClient(
      <MapImageSection
        projectId="p-1"
        wmsUrl="https://geodienste.hamburg.de/HH_WMS_Geobasiskarten"
        datasetId="ds-42"
        onAdded={onAdded}
      />,
    )
    const user = userEvent.setup()

    // Checked in reverse of the service's own order -- the request must still list
    // them bottom-to-top, not in click order (contract: "layers ist die Reihenfolge,
    // in der der Dienst zeichnet").
    await user.click(await screen.findByRole('checkbox', { name: /M2500/ }))
    await user.click(screen.getByRole('checkbox', { name: /Geobasiskarten \(farbig\)/ }))
    await user.click(screen.getByRole('button', { name: 'Als Kartenbild hinzufügen' }))

    await waitFor(() => expect(onAdded).toHaveBeenCalledWith('img-1'))

    const createRequest = requests.find((request) => request.url.includes('/map-layers'))
    expect(createRequest).toBeDefined()
    const body = JSON.parse(createRequest!.init!.body as string)
    expect(body).toEqual({
      serviceUrl: 'https://geodienste.hamburg.de/HH_WMS_Geobasiskarten',
      layers: ['geobasiskarten_farbig', 'm2500_farbig'],
      imageFormat: 'image/png',
      datasetId: 'ds-42',
    })
  })

  test('lässt datasetId weg, wenn keins übergeben wurde (eigene WMS-Adresse)', async () => {
    const { requests } = stubFetch([
      { match: '/api/wms/capabilities', body: CAPABILITIES },
      { match: '/api/projects/p-1/map-layers', body: { id: 'img-1', name: 'Geobasiskarten (farbig)' } },
    ])
    renderWithQueryClient(
      <MapImageSection projectId="p-1" wmsUrl="https://example.org/wms" onAdded={vi.fn()} />,
    )
    const user = userEvent.setup()

    await user.click(await screen.findByRole('checkbox', { name: /Geobasiskarten \(farbig\)/ }))
    await user.click(screen.getByRole('button', { name: 'Als Kartenbild hinzufügen' }))

    await waitFor(() => expect(requests.some((request) => request.url.includes('/map-layers'))).toBe(true))
    const createRequest = requests.find((request) => request.url.includes('/map-layers'))!
    const body = JSON.parse(createRequest.init!.body as string)
    expect(body).not.toHaveProperty('datasetId')
  })
})
