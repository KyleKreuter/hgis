import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { LayerSummary, MapImageLayerSummary } from '@/api/layers'
import { useMapViewport } from '@/map/mapViewportStore'
import { LayerTree } from './LayerTree'

function makeVectorLayer(overrides: Partial<LayerSummary> = {}): LayerSummary {
  return {
    id: 'v-1',
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
    ...overrides,
  }
}

function makeMapImageLayer(overrides: Partial<MapImageLayerSummary> = {}): MapImageLayerSummary {
  return {
    id: 'img-1',
    name: 'Stadtplan',
    kind: 'WMS',
    geometryType: null,
    srid: null,
    featureCount: 0,
    visible: true,
    zIndex: 1,
    minZoom: 0,
    maxZoom: 22,
    dataVersion: 1,
    styleVersion: 1,
    extent: null,
    wms: {
      serviceUrl: 'https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan',
      layers: ['stadtplan'],
      imageFormat: 'image/png',
      legendUrl: null,
      queryable: true,
    },
    ...overrides,
  }
}

function baseProps() {
  return {
    projectId: 'p-1',
    activeLayerId: null,
    onSelectLayer: vi.fn(),
    onZoomToLayer: vi.fn(),
    onImportClick: vi.fn(),
    onCreateLayerClick: vi.fn(),
    onGeoportalClick: vi.fn(),
    onAddMapImageClick: vi.fn(),
  }
}

/** Opens the row's action menu (the "Aktionen für …" button) and returns its content. */
async function openRowMenu(user: ReturnType<typeof userEvent.setup>, layerName: string) {
  await user.click(await screen.findByRole('button', { name: `Aktionen für ${layerName}` }))
  return screen.findByRole('menu')
}

describe('LayerTree mit einem Kartenbild', () => {
  test('zeigt kein Objektzahl-Badge und markiert die Zeile als Kartenbild', async () => {
    stubFetch([{ match: '/api/projects/p-1/layers', body: [makeMapImageLayer()] }])
    renderWithQueryClient(<LayerTree {...baseProps()} />)

    expect(await screen.findByTitle('Stadtplan (Kartenbild)')).toBeInTheDocument()
  })

  test('lässt Felder verwalten, Zuschnitt und Exportieren weg, behält Zoom/Umbenennen/Hintergrundkarte/Löschen', async () => {
    stubFetch([{ match: '/api/projects/p-1/layers', body: [makeMapImageLayer()] }])
    renderWithQueryClient(<LayerTree {...baseProps()} />)
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Stadtplan')

    expect(within(menu).getByRole('menuitem', { name: /Auf Layer zoomen/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Umbenennen/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Hintergrundkarte/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Nach oben/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Löschen/ })).toBeInTheDocument()

    expect(within(menu).queryByRole('menuitem', { name: /Felder verwalten/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Zuschnitt für alles darüber/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Layer exportieren/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Auswahl exportieren/ })).not.toBeInTheDocument()
  })

  test('behält für einen Vektorlayer alle bisherigen Menüpunkte', async () => {
    stubFetch([{ match: '/api/projects/p-1/layers', body: [makeVectorLayer()] }])
    renderWithQueryClient(<LayerTree {...baseProps()} />)
    const user = userEvent.setup()

    const menu = await openRowMenu(user, 'Gebäude')

    expect(within(menu).getByRole('menuitem', { name: /Felder verwalten/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Zuschnitt für alles darüber/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Layer exportieren/ })).toBeInTheDocument()
  })
})

describe('LayerTree: das Auge am Kartenbild', () => {
  test('sagt beim Zoom außerhalb des Fensters, ab wann der Layer zeichnet', async () => {
    useMapViewport.setState({ bbox: null, zoom: 9.8 })
    stubFetch([{ match: '/api/projects/p-1/layers', body: [makeMapImageLayer({ minZoom: 16 })] }])
    renderWithQueryClient(<LayerTree {...baseProps()} />)

    expect(await screen.findByTitle('Sichtbar ab Zoom 16 — Sie sind bei 10.')).toBeInTheDocument()
  })

  test('meldet innerhalb des Fensters, dass gezeichnet wird', async () => {
    useMapViewport.setState({ bbox: null, zoom: 13 })
    stubFetch([{ match: '/api/projects/p-1/layers', body: [makeMapImageLayer({ minZoom: 11 })] }])
    renderWithQueryClient(<LayerTree {...baseProps()} />)

    expect(await screen.findByTitle('Wird bei diesem Zoom gezeichnet')).toBeInTheDocument()
  })

  test('zeigt gar nichts, solange die Karte keinen Zoom gemeldet hat', async () => {
    // Eine Aussage darueber, was der Nutzer sieht, waere hier geraten.
    useMapViewport.setState({ bbox: null, zoom: null })
    stubFetch([{ match: '/api/projects/p-1/layers', body: [makeMapImageLayer({ minZoom: 16 })] }])
    renderWithQueryClient(<LayerTree {...baseProps()} />)

    await screen.findByTitle('Stadtplan (Kartenbild)')
    expect(screen.queryByTitle(/Sichtbar ab Zoom/)).toBeNull()
    expect(screen.queryByTitle(/Wird bei diesem Zoom/)).toBeNull()
  })

  test('hängt einem Vektorlayer kein Auge an, sondern seine Objektzahl', async () => {
    useMapViewport.setState({ bbox: null, zoom: 5 })
    stubFetch([{ match: '/api/projects/p-1/layers', body: [makeVectorLayer({ minZoom: 16 })] }])
    renderWithQueryClient(<LayerTree {...baseProps()} />)

    expect(await screen.findByText('42')).toBeInTheDocument()
    expect(screen.queryByTitle(/Sichtbar ab Zoom/)).toBeNull()
  })
})
