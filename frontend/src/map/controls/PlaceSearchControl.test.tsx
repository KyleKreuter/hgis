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

/**
 * The same street once as a street and once as a house number -- what the backend answers
 * with for `Eickhoffweg 12` (API-Contract "Hausnummern in der Ortssuche"): `name` carries
 * street and number together, `context` keeps the street format `Ortsteil, Postleitzahl`.
 */
const ADDRESS_PLACES: Place[] = [
  {
    name: 'Eickhoffweg 12',
    context: 'Wandsbek, 22041',
    lng: 10.0936,
    lat: 53.5769,
    source: 'hamburg',
    kind: 'address',
  },
  {
    name: 'Eickhoffweg',
    context: 'Wandsbek, 22041',
    lng: 10.0512,
    lat: 53.5871,
    source: 'hamburg',
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

  // A house number is a fourth `kind`, not a street with a suffix: it has to be readable
  // as an address from the row itself, both in the label and in the icon. The two icons
  // are compared against each other rather than matched against a class name, because the
  // failure worth catching is "address reuses the street icon", which a class-name check
  // would only notice by accident.
  test('weist einen Hausnummer-Treffer als Adresse aus', async () => {
    stubFetch([{ match: '/api/places', body: { places: ADDRESS_PLACES } }])
    renderWithQueryClient(<PlaceSearchControl onSelect={vi.fn()} onClear={vi.fn()} />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: NAME }), 'Eickhoffweg 12')

    const options = await screen.findAllByRole('option')
    expect(within(options[0]).getByText('Eickhoffweg 12')).toBeInTheDocument()
    expect(within(options[0]).getByTitle('Adresse')).toBeInTheDocument()
    expect(within(options[0]).getByText('Wandsbek, 22041')).toBeInTheDocument()
    expect(within(options[1]).getByTitle('Straße')).toBeInTheDocument()

    const addressIcon = options[0].querySelector('svg')
    const streetIcon = options[1].querySelector('svg')
    expect(addressIcon).not.toBeNull()
    expect(addressIcon?.innerHTML).not.toEqual(streetIcon?.innerHTML)
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

  // Regression: a real browser blurs the input on `mousedown` for ANY click target
  // that is not itself focusable -- moving focus to `document.body`, exactly what
  // `@testing-library/user-event` v14 reproduces too (unlike a bare `fireEvent.click`,
  // which skips the pointer/focus sequence entirely). A click on the option row
  // therefore used to blur the input first, the resulting `onBlur` closed the panel
  // (`setOpen(false)`), and the `click` event that follows `mouseup` found nothing left
  // in the DOM to land on -- `onSelect` never ran, only visible in a real browser or
  // with `user.click`. `getByRole('option')` after this bug is fixed proves the row
  // still exists to click in the first place.
  test('wählt einen Treffer per Mausklick aus', async () => {
    stubFetch([{ match: '/api/places', body: { places: PLACES } }])
    const onSelect = vi.fn()
    renderWithQueryClient(<PlaceSearchControl onSelect={onSelect} onClear={vi.fn()} />)
    const user = userEvent.setup()
    const input = screen.getByRole('combobox', { name: NAME })

    await user.type(input, 'Eic')
    const options = await screen.findAllByRole('option')
    // Clicks the row's own text, not the `<li>` wrapper `options[1]` itself: the click
    // and mousedown handlers sit on the inner `<div>`, and a click dispatched straight
    // at an ancestor never bubbles back down into a descendant to reach them -- the
    // point of clicking here is to land where a user's pointer actually would.
    await user.click(within(options[1]).getByText('Sudwalde, 27257'))

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

  /**
   * The spinner used to take the clear button's place while a search was running, and the
   * two are not the same shape -- measured in the browser, the X sits at x=1078 and is
   * 24px wide, the spinner sat at x=1082 and was 14px. Every keystroke therefore shifted
   * the icon sideways, shrank it and set it spinning, then restored the X: a flicker that
   * runs for as long as someone keeps typing. The spinner now replaces the magnifier on
   * the left instead, so nothing on the right ever moves.
   *
   * The button's continued presence is the testable half of that. Where it sits is not:
   * jsdom computes no layout, so `getBoundingClientRect` returns zeroes and a position
   * assertion here would pass no matter which side the spinner is on.
   */
  test('behält den Löschknopf, während gesucht wird', async () => {
    let antwort: (value: unknown) => void = () => {}
    const fetchStub = vi.fn(
      () =>
        new Promise((resolve) => {
          antwort = () =>
            resolve({
              ok: true,
              status: 200,
              headers: new Headers({ 'content-type': 'application/json' }),
              json: async () => ({ places: PLACES }),
            })
        }),
    )
    vi.stubGlobal('fetch', fetchStub)
    renderWithQueryClient(<PlaceSearchControl onSelect={vi.fn()} onClear={vi.fn()} />)
    const user = userEvent.setup()

    await user.type(screen.getByRole('combobox', { name: NAME }), 'Eic')
    await vi.waitFor(() => expect(fetchStub).toHaveBeenCalled())

    // Mitten in der laufenden Suche: der Knopf muss stehen bleiben.
    expect(screen.getByRole('button', { name: 'Suche löschen' })).toBeInTheDocument()

    antwort(undefined)
    expect(await screen.findAllByRole('option')).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'Suche löschen' })).toBeInTheDocument()
  })
})
