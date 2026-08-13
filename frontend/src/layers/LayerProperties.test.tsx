import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test } from 'vitest'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import { isVectorLayer, type LayerSummary, type MapImageLayerSummary } from '@/api/layers'
import { SymbologyPanel } from '@/styling'
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

  test('zeigt für ein Kartenbild einen Deckkraftregler, für einen Vektorlayer keinen', () => {
    stubFetch([])
    const { rerender } = renderWithQueryClient(
      <LayerProperties layer={makeMapImageLayer()} projectId="p-1" />,
    )

    const slider = screen.getByRole('slider', { name: 'Deckkraft des Kartenbilds' })
    expect(slider).toBeInTheDocument()
    // Volle Deckkraft ist der Ausgangswert, solange kein style.opacity gesetzt ist.
    expect(screen.getByText('100 %')).toBeInTheDocument()

    rerender(<LayerProperties layer={makeVectorLayer()} projectId="p-1" />)
    expect(screen.queryByText('Deckkraft')).not.toBeInTheDocument()
    expect(screen.queryByRole('slider')).not.toBeInTheDocument()
  })

  test('schreibt eine per Tastatur geänderte Deckkraft an /api/layers/{id}, ohne styleVersion zu erwarten', async () => {
    const { requests } = stubFetch([
      { match: '/api/layers/img-1', body: { ...makeMapImageLayer(), style: { opacity: 0.85 } } },
    ])
    renderWithQueryClient(<LayerProperties layer={makeMapImageLayer()} projectId="p-1" />)
    const user = userEvent.setup()

    const slider = screen.getByRole('slider', { name: 'Deckkraft des Kartenbilds' })
    slider.focus()
    await user.keyboard('{ArrowLeft}')

    await waitFor(() => expect(requests.length).toBeGreaterThan(0))
    const request = requests[0]
    expect(request.url).toContain('/api/layers/img-1')
    const body = JSON.parse(request.init!.body as string)
    // Genau das Vertrags-Addendum: style enthält ausschließlich opacity.
    expect(Object.keys(body)).toEqual(['style'])
    expect(Object.keys(body.style)).toEqual(['opacity'])
    expect(body.style.opacity).toBeCloseTo(0.95)
  })
})

/**
 * Mirrors the one line in `routes/projects.$projectId.tsx` that decides whether the
 * dock shows a symbology panel next to `LayerProperties` -- `activeVectorLayer &&
 * <SymbologyPanel .../>`, narrowed through the very same `isVectorLayer`. The Deckkraft
 * regler just added to `LayerProperties` lives on the layer-properties side precisely
 * so this stays true: a symbology panel is not a place a Kartenbild's own opacity
 * control could be added to without also reopening the door to categorised renderers,
 * labels and the rest of "Symbologie" the contract explicitly rules out for it.
 */
function PropertiesDock({ layer, projectId }: { layer: LayerSummary; projectId: string }) {
  return (
    <div>
      <LayerProperties layer={layer} projectId={projectId} />
      {isVectorLayer(layer) && <SymbologyPanel layer={layer} projectId={projectId} />}
    </div>
  )
}

describe('Eigenschaften-Dock ohne Symbologie für ein Kartenbild', () => {
  test('bekommt für ein Kartenbild keinen Symbologie-Bereich', async () => {
    stubFetch([])
    renderWithQueryClient(<PropertiesDock layer={makeMapImageLayer()} projectId="p-1" />)

    // Der Deckkraftregler in LayerProperties ist da -- nur der Symbologie-Bereich fehlt.
    expect(await screen.findByRole('slider', { name: 'Deckkraft des Kartenbilds' })).toBeInTheDocument()
    expect(screen.queryByText('Darstellung')).not.toBeInTheDocument()
    expect(screen.queryByText('Beschriftung')).not.toBeInTheDocument()
  })

  test('zeigt für einen Vektorlayer weiterhin den Symbologie-Bereich', async () => {
    stubFetch([
      {
        match: '/api/layers/layer-1',
        body: { ...makeVectorLayer(), fields: [], createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
      },
    ])
    renderWithQueryClient(<PropertiesDock layer={makeVectorLayer()} projectId="p-1" />)

    expect(await screen.findByText('Darstellung')).toBeInTheDocument()
    expect(screen.getByText('Beschriftung')).toBeInTheDocument()
  })
})
