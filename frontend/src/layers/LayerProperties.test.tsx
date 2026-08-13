import { screen } from '@testing-library/react'
import { describe, expect, test } from 'vitest'
import { renderWithQueryClient } from '@/test/render'
import type { LayerSummary, MapImageLayerSummary } from '@/api/layers'
import { LayerProperties } from './LayerProperties'

function makeVectorLayer(): LayerSummary {
  return {
    id: 'layer-1',
    name: 'Gebäude',
    geometryType: 'MULTIPOLYGON',
    srid: 25832,
    featureCount: 42,
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
      serviceUrl: 'https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan',
      layers: ['stadtplan', 'beschriftung'],
      imageFormat: 'image/png',
      legendUrl: null,
      queryable: true,
    },
  }
}

describe('LayerProperties', () => {
  test('zeigt für einen Vektorlayer Objektzahl und CRS', () => {
    renderWithQueryClient(<LayerProperties layer={makeVectorLayer()} projectId="p-1" />)

    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('EPSG:25832')).toBeInTheDocument()
    expect(screen.queryByText('Dienst')).not.toBeInTheDocument()
  })

  test('zeigt für ein Kartenbild die Dienstadresse und die gewählten Layer statt Objektzahl und CRS', () => {
    renderWithQueryClient(<LayerProperties layer={makeMapImageLayer()} projectId="p-1" />)

    expect(screen.getByText('https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan')).toBeInTheDocument()
    expect(screen.getByText('stadtplan, beschriftung')).toBeInTheDocument()
    expect(screen.queryByText('Objekte')).not.toBeInTheDocument()
    expect(screen.queryByText('CRS')).not.toBeInTheDocument()
  })

  test('zeigt das Zoomfenster für beide Layerarten gleich', () => {
    renderWithQueryClient(<LayerProperties layer={makeMapImageLayer()} projectId="p-1" />)

    expect(screen.getByText('Zoom')).toBeInTheDocument()
    expect(
      screen.getByText('Außerhalb dieses Zoomfensters blendet das Programm den Layer aus.'),
    ).toBeInTheDocument()
  })
})
