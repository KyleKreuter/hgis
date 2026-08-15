import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { Place } from '@/api/places'
import { PlaceSearchControl } from './PlaceSearchControl'

/**
 * Two hits sharing a name, the exact scenario CONTRACT.md names as the reason `context`
 * must never be dropped: without it, two "Eickhoffweg" rows would be indistinguishable.
 */
const PLACES: Place[] = [
  {
    name: 'Eickhoffweg',
    context: 'Hamburg-Nord, 22041',
    lng: 10.0512,
    lat: 53.5871,
    source: 'hamburg',
    kind: 'street',
  },
  {
    name: 'Eickhoffweg',
    context: 'Sudwalde, 27257',
    lng: 8.8342,
    lat: 52.8461,
    source: 'photon',
    kind: 'street',
  },
]

const NAME = 'Straße oder Ort suchen'

/**
 * Driven through the real `usePlaceSearch` hook with only `fetch` stubbed, the same rule
 * `MapImageSection.test.tsx` follows -- this fails the moment the component stops
 * asking for what the contract actually promises.
 */
describe('PlaceSearchControl', () => {
  test('sucht nicht vor der dritten Eingabe', async () => {
    const { fetchStub } = stubFetch([{ match: '/api/places', body: { places: PLACES } }])
    renderWithQueryClient(<PlaceSearchControl onSelect={vi.fn()} onClear={vi.fn()} />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: NAME }), 'Ei')

    // Long enough for the 300 ms debounce to have fired if it were going to -- it must not.
    await new Promise((resolve) => setTimeout(resolve, 400))
    expect(fetchStub).not.toHaveBeenCalled()
  })

  test('zeigt Kontext und Herkunft zu jedem Treffer', async () => {
    stubFetch([{ match: '/api/places', body: { places: PLACES } }])
    renderWithQueryClient(<PlaceSearchControl onSelect={vi.fn()} onClear={vi.fn()} />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: NAME }), 'Eic')

    const options = await screen.findAllByRole('option')
    expect(options).toHaveLength(2)
    expect(within(options[0]).getByText('Hamburg amtlich')).toBeInTheDocument()
    const context = within(options[0]).getByText('Hamburg-Nord, 22041')
    expect(context).toBeInTheDocument()
    // CONTRACT.md: "er darf nie wegfallen oder abgeschnitten werden, solange Platz
    // ist" -- a CSS `truncate` here would leave the text in the DOM but clip it
    // visually, which `getByText` above cannot catch on its own.
    expect(context.className).not.toContain('truncate')
    expect(within(options[1]).getByText('OpenStreetMap')).toBeInTheDocument()
    expect(within(options[1]).getByText('Sudwalde, 27257')).toBeInTheDocument()
  })

  test('wählt einen Treffer per Pfeiltasten und Eingabetaste', async () => {
    stubFetch([{ match: '/api/places', body: { places: PLACES } }])
    const onSelect = vi.fn()
    renderWithQueryClient(<PlaceSearchControl onSelect={onSelect} onClear={vi.fn()} />)
    const user = userEvent.setup()
    const input = screen.getByRole('combobox', { name: NAME })

    await user.type(input, 'Eic')
    await screen.findAllByRole('option')
    // Down twice lands on the second row (Photon/Sudwalde), not the first.
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')

    expect(onSelect).toHaveBeenCalledWith(PLACES[1])
    expect(input).toHaveValue('Eickhoffweg')
  })

  test('nimmt bei einer bloßen Eingabetaste den ersten Treffer', async () => {
    stubFetch([{ match: '/api/places', body: { places: PLACES } }])
    const onSelect = vi.fn()
    renderWithQueryClient(<PlaceSearchControl onSelect={onSelect} onClear={vi.fn()} />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: NAME }), 'Eic')
    await screen.findAllByRole('option')
    await user.keyboard('{Enter}')

    expect(onSelect).toHaveBeenCalledWith(PLACES[0])
  })

  test('schließt die Trefferliste mit Escape, ohne den Text zu löschen', async () => {
    stubFetch([{ match: '/api/places', body: { places: PLACES } }])
    renderWithQueryClient(<PlaceSearchControl onSelect={vi.fn()} onClear={vi.fn()} />)
    const user = userEvent.setup()
    const input = screen.getByRole('combobox', { name: NAME })

    await user.type(input, 'Eic')
    await screen.findAllByRole('option')
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('option')).not.toBeInTheDocument()
    expect(input).toHaveValue('Eic')
  })

  test('löscht Text und Auswahl über die Schaltfläche', async () => {
    stubFetch([{ match: '/api/places', body: { places: PLACES } }])
    const onClear = vi.fn()
    renderWithQueryClient(<PlaceSearchControl onSelect={vi.fn()} onClear={onClear} />)
    const user = userEvent.setup()
    const input = screen.getByRole('combobox', { name: NAME })

    await user.type(input, 'Eic')
    await screen.findAllByRole('option')
    await user.click(screen.getByRole('button', { name: 'Suche löschen' }))

    expect(input).toHaveValue('')
    expect(onClear).toHaveBeenCalledOnce()
    expect(screen.queryByRole('option')).not.toBeInTheDocument()
  })

  test('meldet, wenn kein Ort gefunden wurde', async () => {
    stubFetch([{ match: '/api/places', body: { places: [] } }])
    renderWithQueryClient(<PlaceSearchControl onSelect={vi.fn()} onClear={vi.fn()} />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: NAME }), 'Xyz')

    expect(await screen.findByText('Kein Ort gefunden.')).toBeInTheDocument()
  })

  // Measured against the real backend (endpoint not deployed yet): a 404 answers RFC
  // 7807 with `detail: "Die Ressource 'api/places' existiert nicht"` -- an internal path
  // name, phrased like a crash. A search box must never repeat that to the user.
  test('zeigt bei einem Fehler eine allgemeine Meldung, nie den rohen Server-Text', async () => {
    stubFetch([
      {
        match: '/api/places',
        status: 404,
        body: { title: 'Nicht gefunden', status: 404, detail: "Die Ressource 'api/places' existiert nicht" },
      },
    ])
    renderWithQueryClient(<PlaceSearchControl onSelect={vi.fn()} onClear={vi.fn()} />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: NAME }), 'Xyz')

    expect(await screen.findByText('Die Ortssuche ist gerade nicht erreichbar.')).toBeInTheDocument()
    expect(screen.queryByText(/existiert nicht/)).not.toBeInTheDocument()
    expect(screen.queryByText(/api\/places/)).not.toBeInTheDocument()
  })
})
