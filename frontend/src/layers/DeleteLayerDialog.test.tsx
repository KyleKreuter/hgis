import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { LayerSummary, MapImageLayerSummary } from '@/api/layers'
import { DeleteLayerDialog } from './DeleteLayerDialog'

function makeVectorLayer(): LayerSummary {
  return {
    id: 'v-1',
    name: 'Gebäude',
    geometryType: 'MULTIPOLYGON',
    srid: 25832,
    featureCount: 12,
    visible: true,
    zIndex: 0,
    minZoom: 0,
    maxZoom: 22,
    dataVersion: 1,
    styleVersion: 1,
    extent: null,
  }
}

function makeMapImageLayer(): MapImageLayerSummary {
  return {
    id: 'img-1',
    name: 'Stadtplan',
    kind: 'WMS',
    geometryType: null,
    srid: null,
    featureCount: 0,
    visible: true,
    zIndex: 0,
    minZoom: 0,
    maxZoom: 22,
    dataVersion: 1,
    styleVersion: 1,
    extent: null,
    wms: {
      serviceUrl: 'https://example.org/wms',
      layers: ['a'],
      imageFormat: 'image/png',
      legendUrl: null,
      queryable: true,
    },
  }
}

describe('DeleteLayerDialog', () => {
  test('nennt für einen Vektorlayer die Objektzahl', () => {
    renderWithQueryClient(
      <DeleteLayerDialog layer={makeVectorLayer()} projectId="p-1" onOpenChange={vi.fn()} onDeleted={vi.fn()} />,
    )

    expect(screen.getByText(/mit/)).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText(/Objekten in den Papierkorb/)).toBeInTheDocument()
  })

  test('nennt für ein Kartenbild keine Objektzahl', () => {
    renderWithQueryClient(
      <DeleteLayerDialog layer={makeMapImageLayer()} projectId="p-1" onOpenChange={vi.fn()} onDeleted={vi.fn()} />,
    )

    expect(screen.getByText(/verschiebt „Stadtplan" in den Papierkorb/)).toBeInTheDocument()
    expect(screen.queryByText(/Objekt/)).not.toBeInTheDocument()
  })

  // Vertragswechsel (Paket 1 `schutz`): DELETE /api/layers/{id} antwortet künftig 200
  // mit dem TrashEntry statt wie bisher 204 ohne Rumpf. Gegen beide Formen gebaut, hier
  // auch gegen beide geprüft -- ein Server, der die Umstellung noch nicht ausgeliefert
  // hat, darf den Löschvorgang nicht brechen.
  test('übersteht die heutige 204-Antwort ohne Rumpf', async () => {
    stubFetch([{ match: '/api/layers/v-1', body: undefined, status: 204 }])
    const onDeleted = vi.fn()
    renderWithQueryClient(
      <DeleteLayerDialog
        layer={makeVectorLayer()}
        projectId="p-1"
        onOpenChange={vi.fn()}
        onDeleted={onDeleted}
      />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Löschen' }))

    await waitFor(() => expect(onDeleted).toHaveBeenCalledWith('v-1'))
  })

  test('übersteht die künftige 200-Antwort mit TrashEntry-Rumpf', async () => {
    stubFetch([
      {
        match: '/api/layers/v-1',
        body: {
          id: 'v-1',
          name: 'Gebäude',
          deletedAt: '2026-08-16T00:00:00Z',
          deletedBy: 'M. Mustermann',
          featureCount: 12,
        },
      },
    ])
    const onDeleted = vi.fn()
    renderWithQueryClient(
      <DeleteLayerDialog
        layer={makeVectorLayer()}
        projectId="p-1"
        onOpenChange={vi.fn()}
        onDeleted={onDeleted}
      />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Löschen' }))

    await waitFor(() => expect(onDeleted).toHaveBeenCalledWith('v-1'))
  })
})
