import { screen } from '@testing-library/react'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient } from '@/test/render'
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
})
