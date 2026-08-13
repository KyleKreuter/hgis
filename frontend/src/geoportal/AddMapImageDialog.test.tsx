import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { WmsCapabilities } from '@/api/wms'
import { AddMapImageDialog } from './AddMapImageDialog'

const CAPABILITIES: WmsCapabilities = {
  serviceUrl: 'https://example.org/wms',
  title: 'Ein Dienst',
  version: '1.3.0',
  imageFormats: ['image/png'],
  layers: [
    { name: 'a', title: 'Layer A', depth: 0, queryable: true, legendUrl: null, minScale: null, maxScale: null, bbox: null },
  ],
}

describe('AddMapImageDialog', () => {
  test('fragt die Dienstbeschreibung erst ab, wenn "Layer laden" gedrückt wird', async () => {
    const { calls } = stubFetch([{ match: '/api/wms/capabilities', body: CAPABILITIES }])
    renderWithQueryClient(
      <AddMapImageDialog projectId="p-1" open onOpenChange={vi.fn()} onCreated={vi.fn()} />,
    )
    const user = userEvent.setup()

    // No request yet -- typing alone must not cost the target service a round trip.
    await user.type(screen.getByLabelText('Dienstadresse'), 'https://example.org/wms')
    expect(calls.some((url) => url.includes('/api/wms/capabilities'))).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Layer laden' }))

    expect(await screen.findByText('Layer A')).toBeInTheDocument()
    expect(calls.some((url) => url.includes('/api/wms/capabilities'))).toBe(true)
  })

  test('lässt den Ladeknopf gesperrt, solange das Feld leer ist', () => {
    stubFetch([])
    renderWithQueryClient(<AddMapImageDialog projectId="p-1" open onOpenChange={vi.fn()} onCreated={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Layer laden' })).toBeDisabled()
  })
})
