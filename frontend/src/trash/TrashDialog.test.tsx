import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { TrashEntry } from '@/api/trash'
import { TrashDialog } from './TrashDialog'

function makeEntry(overrides: Partial<TrashEntry> = {}): TrashEntry {
  return {
    id: 'l-1',
    name: 'Gebäude',
    deletedAt: new Date().toISOString(),
    deletedBy: 'M. Mustermann',
    featureCount: 12,
    ...overrides,
  }
}

describe('TrashDialog', () => {
  test('meldet den leeren Papierkorb statt einer leeren Fläche', async () => {
    stubFetch([{ match: '/api/projects/p-1/trash', body: [] }])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)

    expect(await screen.findByText('Der Papierkorb ist leer')).toBeInTheDocument()
  })

  test('meldet einen Ladefehler statt still zu bleiben', async () => {
    stubFetch([{ match: '/api/projects/p-1/trash', body: {}, status: 500 }])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)

    expect(
      await screen.findByText('Der Papierkorb konnte nicht geladen werden'),
    ).toBeInTheDocument()
  })

  test('zeigt Name, Löschzeitpunkt, wer gelöscht hat und die Objektzahl je Eintrag', async () => {
    stubFetch([
      {
        match: '/api/projects/p-1/trash',
        body: [makeEntry({ name: 'Gebäude', deletedBy: 'M. Mustermann', featureCount: 12 })],
      },
    ])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)

    expect(await screen.findByText('Gebäude')).toBeInTheDocument()
    expect(screen.getByText('M. Mustermann')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
  })

  test('zeigt einen neutralen Platzhalter, wenn niemand als Löscher bekannt ist', async () => {
    stubFetch([
      { match: '/api/projects/p-1/trash', body: [makeEntry({ deletedBy: null })] },
    ])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)

    expect(await screen.findByText('Gebäude')).toBeInTheDocument()
    expect(screen.getByText('–')).toBeInTheDocument()
  })

  test('stellt einen Layer über POST /api/layers/{id}/restore wieder her', async () => {
    const { requests } = stubFetch([
      { match: '/api/projects/p-1/trash', body: [makeEntry()] },
      { match: '/api/layers/l-1/restore', body: {} },
    ])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Layer „Gebäude" wiederherstellen' }))

    await waitFor(() =>
      expect(requests.some((r) => r.url.includes('/api/layers/l-1/restore'))).toBe(true),
    )
    const restoreRequest = requests.find((r) => r.url.includes('/restore'))
    expect(restoreRequest?.init?.method).toBe('POST')
  })

  test('endgültig löschen fragt erst nach und nennt die Objektzahl', async () => {
    stubFetch([{ match: '/api/projects/p-1/trash', body: [makeEntry({ featureCount: 7 })] }])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole('button', { name: 'Layer „Gebäude" endgültig löschen' }),
    )

    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText('7')).toBeInTheDocument()
    expect(within(dialog).getByText(/lässt sich nicht rückgängig machen/)).toBeInTheDocument()
  })

  test('bestätigtes endgültiges Löschen ruft DELETE /api/layers/{id}/purge auf', async () => {
    const { requests } = stubFetch([
      { match: '/api/projects/p-1/trash', body: [makeEntry()] },
      { match: '/api/layers/l-1/purge', body: undefined, status: 204 },
    ])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole('button', { name: 'Layer „Gebäude" endgültig löschen' }),
    )
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Endgültig löschen' }))

    await waitFor(() =>
      expect(requests.some((r) => r.url.includes('/api/layers/l-1/purge'))).toBe(true),
    )
    const purgeRequest = requests.find((r) => r.url.includes('/purge'))
    expect(purgeRequest?.init?.method).toBe('DELETE')
  })

  // Prüfstand-Befund: `disabled={mutation.isPending}` greift erst nach dem nächsten
  // React-Render. Zwei Klicks, die schneller kommen als dieser Render, gingen beide
  // durch und lösten zwei Server-Aufrufe aus. Reproduziert mit derselben Technik, mit
  // der das gefunden wurde: zwei `user.click` ohne `await` dazwischen.
  //
  // `delayMs` ist hier kein Zufall: eine sofort auflösende Mock-Antwort lässt den ersten
  // Klick komplett fertig laufen (inklusive `finally`), bevor `userEvent`s eigene, über
  // mehrere echte Ticks laufende Klick-Choreografie den zweiten Klick überhaupt auslöst
  // -- dann überschneiden sich die beiden Aufrufe gar nicht erst, und die Sperre sähe
  // wirkungslos aus, obwohl nichts sie herausgefordert hat. Ein paar Millisekunden
  // Verzögerung, näherungsweise ein echter Umlauf, hält den ersten Aufruf lange genug
  // offen, damit der zweite tatsächlich mitten hineinläuft.
  test('zwei schnelle Klicks auf Wiederherstellen lösen nur einen POST aus', async () => {
    const { requests } = stubFetch([
      { match: '/api/projects/p-1/trash', body: [makeEntry()] },
      { match: '/api/layers/l-1/restore', body: {}, delayMs: 20 },
    ])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)
    const user = userEvent.setup()
    const button = await screen.findByRole('button', { name: 'Layer „Gebäude" wiederherstellen' })

    await Promise.all([user.click(button), user.click(button)])

    expect(requests.filter((r) => r.url.includes('/restore')).length).toBe(1)
  })

  test('zwei schnelle Klicks auf die Löschbestätigung lösen nur einen DELETE aus', async () => {
    const { requests } = stubFetch([
      { match: '/api/projects/p-1/trash', body: [makeEntry()] },
      { match: '/api/layers/l-1/purge', body: undefined, status: 204, delayMs: 20 },
    ])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole('button', { name: 'Layer „Gebäude" endgültig löschen' }),
    )
    const dialog = await screen.findByRole('alertdialog')
    const confirmButton = within(dialog).getByRole('button', { name: 'Endgültig löschen' })

    await Promise.all([user.click(confirmButton), user.click(confirmButton)])

    expect(requests.filter((r) => r.url.includes('/purge')).length).toBe(1)
  })

  test('behält die Zeile, wenn Wiederherstellen serverseitig fehlschlägt', async () => {
    stubFetch([
      { match: '/api/projects/p-1/trash', body: [makeEntry()] },
      { match: '/api/layers/l-1/restore', body: {}, status: 500 },
    ])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)
    const user = userEvent.setup()
    const button = await screen.findByRole('button', { name: 'Layer „Gebäude" wiederherstellen' })

    await user.click(button)

    // Kein optimistisches Entfernen bei einem fehlgeschlagenen Restore -- die Zeile, und
    // mit ihr der Knopf, muss stehen bleiben, statt aus dem Cache verschwunden zu sein.
    await waitFor(() => expect(button).not.toBeDisabled())
    expect(screen.getByTitle('Gebäude')).toBeInTheDocument()
  })

  test('bricht bei einem Serverfehler ab, ohne den Layer aus dem Papierkorb zu nehmen', async () => {
    stubFetch([
      { match: '/api/projects/p-1/trash', body: [makeEntry()] },
      { match: '/api/layers/l-1/purge', body: {}, status: 500 },
    ])
    renderWithQueryClient(<TrashDialog projectId="p-1" open onOpenChange={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole('button', { name: 'Layer „Gebäude" endgültig löschen' }),
    )
    const dialog = await screen.findByRole('alertdialog')
    const confirmButton = within(dialog).getByRole('button', { name: 'Endgültig löschen' })
    await user.click(confirmButton)

    // Ein fehlgeschlagenes Purge schließt die Rückfrage nicht -- das wäre die einzige
    // sichtbare Bestätigung, dass etwas passiert ist, obwohl nichts gelöscht wurde.
    await waitFor(() => expect(confirmButton).not.toBeDisabled())
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByTitle('Gebäude')).toBeInTheDocument()
  })
})
