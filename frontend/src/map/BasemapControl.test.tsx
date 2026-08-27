import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test } from 'vitest'
import { renderWithQueryClient, stubFetch, testQueryClient } from '@/test/render'
import { basemapKeys } from '@/api/basemaps'
import { BasemapControl } from './BasemapControl'
import { TEST_BASEMAP_CATALOG } from './testBasemapCatalog'

const PROJECT_ID = 'p-1'

function renderControl() {
  const client = testQueryClient()
  client.setQueryData(basemapKeys.all, TEST_BASEMAP_CATALOG)
  return renderWithQueryClient(
    <BasemapControl
      projectId={PROJECT_ID}
      project={{ basemap: 'osm', basemapOpacity: 1 }}
      activeLayer={null}
    />,
    client,
  )
}

async function openMenu() {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: /Hintergrundkarte:/ }))
  return user
}

describe('BasemapControl', () => {
  test('gruppiert den Katalog und bietet eine eigene Kachel-URL an', async () => {
    renderControl()
    await openMenu()

    expect(await screen.findByText('Deutschland')).toBeInTheDocument()
    expect(screen.getByText('Luft- und Satellitenbild')).toBeInTheDocument()
    expect(screen.getByText('Eigene Kachel-URL…')).toBeInTheDocument()
  })

  test('setzt eine vollständige eigene Kachel-URL als Hintergrundkarte des Projekts', async () => {
    const { requests } = stubFetch([
      { match: '/api/projects/p-1', body: { id: PROJECT_ID, basemap: 'https://tiles.example.test/{z}/{x}/{y}.png' } },
    ])
    renderControl()
    const user = await openMenu()

    await user.click(await screen.findByText('Eigene Kachel-URL…'))
    await screen.findByRole('dialog')
    const input = await screen.findByRole('textbox', { name: 'Eigene Kachel-URL' })
    await user.type(input, 'https://tiles.example.test/{{z}/{{x}/{{y}.png')
    await user.click(screen.getByRole('button', { name: 'Übernehmen' }))

    await waitFor(() => {
      const patch = requests.find((request) => request.url.includes('/api/projects/p-1'))
      expect(patch).toBeDefined()
      expect(JSON.parse(String(patch!.init?.body))).toMatchObject({
        basemap: 'https://tiles.example.test/{z}/{x}/{y}.png',
      })
    })
  })

  test('meldet eine fehlende https-URL, statt einen Wert zu speichern', async () => {
    // No route stubbed at all: a PATCH that slipped through despite the empty field
    // would reject with "No stub route for ...", which surfaces as an unhandled
    // rejection and fails the test on its own.
    stubFetch([])
    renderControl()
    const user = await openMenu()

    await user.click(await screen.findByText('Eigene Kachel-URL…'))
    await user.click(screen.getByRole('button', { name: 'Übernehmen' }))

    expect(await screen.findByText('Eine Kachel-URL ist erforderlich.')).toBeInTheDocument()
  })
})
