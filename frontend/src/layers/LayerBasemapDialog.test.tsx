import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient, stubFetch, testQueryClient } from '@/test/render'
import { basemapKeys } from '@/api/basemaps'
import { projectKeys, type ProjectDetail } from '@/api/projects'
import type { LayerSummary } from '@/api/layers'
import { TEST_BASEMAP_CATALOG } from '@/map/testBasemapCatalog'
import { LayerBasemapDialog } from './LayerBasemapDialog'

const PROJECT_ID = 'p-1'

function makeLayer(overrides: Partial<LayerSummary> = {}): LayerSummary {
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
    ...overrides,
  }
}

function makeProject(overrides: Partial<ProjectDetail> = {}): ProjectDetail {
  return {
    id: PROJECT_ID,
    name: 'Musterstadt',
    description: null,
    srid: 25832,
    layerCount: 1,
    featureCount: 12,
    lastOpenedAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    basemap: 'osm',
    basemapOpacity: 1,
    center: null,
    zoom: null,
    extent: null,
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

/** The catalog and the project are both already in cache in the real app (route
 * loaders), so the dialog itself never fetches either -- this seeds the same state
 * instead of adding fetch stubs nothing here is meant to exercise. */
function renderDialog(layer: LayerSummary, onOpenChange = vi.fn()) {
  const client = testQueryClient()
  client.setQueryData(basemapKeys.all, TEST_BASEMAP_CATALOG)
  client.setQueryData(projectKeys.detail(PROJECT_ID), makeProject())
  return {
    onOpenChange,
    ...renderWithQueryClient(
      <LayerBasemapDialog layer={layer} projectId={PROJECT_ID} onOpenChange={onOpenChange} />,
      client,
    ),
  }
}

async function openBasemapSelect() {
  const user = userEvent.setup()
  await user.click(screen.getByRole('combobox', { name: 'Karte' }))
  return user
}

describe('LayerBasemapDialog', () => {
  test('gruppiert den Katalog nach group, mit Kopfzeile je Gruppe', async () => {
    renderDialog(makeLayer())
    await openBasemapSelect()

    expect(await screen.findByText('Deutschland')).toBeInTheDocument()
    expect(screen.getByText('Luft- und Satellitenbild')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /basemap\.de Grau/ })).toBeInTheDocument()
  })

  test('zeigt den Kontohinweis nur bei requiresAccount', async () => {
    renderDialog(makeLayer())
    await openBasemapSelect()

    const esriOption = await screen.findByRole('option', { name: /Esri World Imagery/ })
    expect(esriOption).toHaveTextContent('ArcGIS-Konto erforderlich')

    const osmOption = screen.getByRole('option', { name: /^OpenStreetMap/ })
    expect(osmOption).not.toHaveTextContent('ArcGIS-Konto erforderlich')
  })

  test('markiert einen abgekündigten Dienst', async () => {
    renderDialog(makeLayer())
    await openBasemapSelect()

    const deprecatedOption = await screen.findByRole('option', { name: /Stamen Toner/ })
    expect(deprecatedOption).toHaveTextContent('Abgekündigt')
  })

  test('nennt die Abdeckung, wenn sie nicht weltweit ist', async () => {
    renderDialog(makeLayer())
    await openBasemapSelect()

    const grauOption = await screen.findByRole('option', { name: /basemap\.de Grau/ })
    expect(grauOption).toHaveTextContent('Nur Deutschland')

    const osmOption = screen.getByRole('option', { name: /^OpenStreetMap/ })
    expect(osmOption).not.toHaveTextContent('Nur')
  })

  // VERTRAG.md: coverage ist nicht auf "DE"/"HH"/"EU"/"world" festgelegt, sondern
  // trägt auch das ISO-3166-2:DE-Kürzel eines Bundeslandes ("BY", "NW", ...).
  test('nennt den vollen Ländernamen für ein Bundesland-Kürzel', async () => {
    renderDialog(makeLayer())
    await openBasemapSelect()

    const byOption = await screen.findByRole('option', { name: /Digitale Orthophotos Bayern/ })
    expect(byOption).toHaveTextContent('Nur Bayern')
  })

  describe('Freitext', () => {
    test('meldet einen fehlenden Platzhalter, statt zu speichern', async () => {
      renderDialog(makeLayer())
      const user = await openBasemapSelect()
      await user.click(await screen.findByRole('option', { name: 'Eigene Kachel-URL…' }))

      const input = screen.getByLabelText('Eigene Kachel-URL')
      // Curly braces are `userEvent.type`'s own syntax for special keys ("{enter}" etc.)
      // -- doubled here so `{z}`/`{x}` land as literal text instead of being swallowed
      // as an unrecognised key sequence.
      await user.type(input, 'https://tiles.example.test/{{z}/{{x}.png')
      await user.click(screen.getByRole('button', { name: 'Speichern' }))

      // Matched against the actual error sentence, not just "{y}" anywhere on the
      // page: the field's own static help text below the input already mentions all
      // three placeholders, so a looser match would pass even if the submit handler
      // stopped checking the URL at all.
      expect(await screen.findByText('Der Platzhalter {y} fehlt in der URL.')).toBeInTheDocument()
    })

    test('speichert eine vollständige eigene Kachel-URL als basemap des Layers', async () => {
      const { requests } = stubFetch([
        { match: '/api/layers/v-1', body: { id: 'v-1', name: 'Gebäude', basemap: null } },
      ])
      const onOpenChange = vi.fn()
      renderDialog(makeLayer(), onOpenChange)
      const user = await openBasemapSelect()
      await user.click(await screen.findByRole('option', { name: 'Eigene Kachel-URL…' }))

      const url = 'https://tiles.example.test/{z}/{x}/{y}.png'
      await user.type(screen.getByLabelText('Eigene Kachel-URL'), 'https://tiles.example.test/{{z}/{{x}/{{y}.png')
      await user.click(screen.getByRole('button', { name: 'Speichern' }))

      await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
      const patch = requests.find((request) => request.url.includes('/api/layers/v-1'))
      expect(patch).toBeDefined()
      expect(JSON.parse(String(patch!.init?.body))).toMatchObject({ basemap: url })
    })
  })
})
