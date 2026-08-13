import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient, stubFetch, type StubRoute } from '@/test/render'
import { SplitConfirmDialog } from './SplitConfirmDialog'

/**
 * The confirmation, driven through the real mutation hook with only `fetch` stubbed --
 * the same line `GeoportalDialog.test.tsx` draws. What the request carries is the point:
 * a test that mocked `useSplitFeature` would prove only that the dialog calls the hook it
 * was written to call, and the `rowVersion` is exactly the field that would go missing
 * without anyone noticing until two people edited the same row.
 */

const LINE: GeoJSON.LineString = {
  type: 'LineString',
  coordinates: [
    [9.98, 53.55],
    [9.99, 53.56],
  ],
}

function renderDialog(routes: StubRoute[]) {
  const onDone = vi.fn()
  const onCancel = vi.fn()
  const onRedraw = vi.fn()
  const stub = stubFetch(routes)

  renderWithQueryClient(
    <SplitConfirmDialog
      layerId="l1"
      projectId="p1"
      fid={7}
      rowVersion="8241"
      line={LINE}
      onRedraw={onRedraw}
      onCancel={onCancel}
      onDone={onDone}
    />,
  )

  return { stub, onDone, onCancel, onRedraw }
}

const confirmButton = () => screen.getByRole('button', { name: 'Teilen' })

describe('SplitConfirmDialog', () => {
  test('sagt vorher, dass sich das nicht rückgängig machen lässt', () => {
    // The whole reason the step exists: this is one of the two actions the editor's undo
    // cannot reach, and by the time anything looks wrong the old geometry is gone.
    renderDialog([])

    expect(screen.getByText(/nicht rückgängig machen/)).toBeInTheDocument()
  })

  test('sendet die gezeichnete Linie und die rowVersion', async () => {
    const { stub, onDone } = renderDialog([
      { match: '/split', body: { fids: [7, 1001], dataVersion: 12 } },
    ])

    await userEvent.click(confirmButton())

    await waitFor(() => expect(onDone).toHaveBeenCalledWith([7, 1001]))
    const request = stub.requests.find((entry) => entry.url.includes('/split'))
    expect(request?.url).toBe('/api/layers/l1/features/7/split')
    expect(request?.init?.method).toBe('POST')
    expect(JSON.parse(String(request?.init?.body))).toEqual({ line: LINE, rowVersion: '8241' })
  })

  test('erklärt einen 409 als fremde Änderung und schreibt nichts fest', async () => {
    const { onDone } = renderDialog([
      {
        match: '/split',
        status: 409,
        body: { detail: 'Eine andere Stelle hat Objekt 7 zwischenzeitlich geändert' },
      },
    ])

    await userEvent.click(confirmButton())

    // The server's own wording is a row version mismatch, which says nothing to whoever
    // is looking at the map. What happened is that someone else got there first.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Ein anderer Benutzer hat das Objekt inzwischen geändert',
    )
    expect(onDone).not.toHaveBeenCalled()
    // The dialog stays open with the line still drawn, so "Neu zeichnen" remains an
    // answer -- a toast on a closed dialog would leave nothing to act on.
    expect(confirmButton()).toBeInTheDocument()
  })

  test('zeigt den Wortlaut des Servers, wenn die Linie danebenliegt', async () => {
    renderDialog([
      { match: '/split', status: 400, body: { detail: 'Die Linie teilt das Objekt nicht.' } },
    ])

    await userEvent.click(confirmButton())

    // Kept verbatim: it names what to do next far better than any generic sentence.
    expect(await screen.findByRole('alert')).toHaveTextContent('Die Linie teilt das Objekt nicht.')
  })

  test('führt über "Neu zeichnen" zurück auf die Karte', async () => {
    const { onRedraw } = renderDialog([])

    await userEvent.click(screen.getByRole('button', { name: 'Neu zeichnen' }))

    expect(onRedraw).toHaveBeenCalled()
  })
})
