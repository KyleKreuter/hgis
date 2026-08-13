import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient, stubFetch, type StubRoute } from '@/test/render'
import type { Feature } from '@/api/features'
import type { LayerField } from '@/api/layers'
import { MergeDialog } from './MergeDialog'

/**
 * The lead chooser, driven through the real query and mutation hooks with only `fetch`
 * stubbed. Two things are under test that no smaller unit could answer: that the choice
 * the user makes is the one that leaves the browser, and that every part's `rowVersion`
 * goes along -- the merge is one transaction, and a missing version would silently turn
 * off the conflict check for that part.
 */

const FIELDS: LayerField[] = [
  { id: 'f1', sourceName: 'Straße', columnName: 'strasse', dataType: 'text' },
  { id: 'f2', sourceName: 'Höhe ü. NN', columnName: 'hoehe', dataType: 'double precision' },
]

function feature(fid: number, rowVersion: string, strasse: string, type = 'Polygon'): Feature {
  return {
    fid,
    rowVersion,
    properties: { strasse, hoehe: 12.5 },
    geometry: { type, coordinates: [] },
  }
}

const FEATURE_42 = feature(42, '8241', 'Alte Landstraße')
const FEATURE_43 = feature(43, '8242', 'Neue Landstraße')

/** Longest match first is not needed here -- no url is a prefix of another. */
function routesFor(extra: StubRoute[] = []): StubRoute[] {
  return [
    ...extra,
    { match: '/features/42', body: FEATURE_42 },
    { match: '/features/43', body: FEATURE_43 },
  ]
}

function renderDialog(routes: StubRoute[]) {
  const onDone = vi.fn()
  const onCancel = vi.fn()
  const stub = stubFetch(routes)

  renderWithQueryClient(
    <MergeDialog
      layerId="l1"
      projectId="p1"
      fids={[42, 43]}
      fields={FIELDS}
      onCancel={onCancel}
      onDone={onDone}
    />,
  )

  return { stub, onDone, onCancel }
}

const confirmButton = () => screen.getByRole('button', { name: 'Zusammenführen' })
const leadRadio = (fid: number) => screen.getByRole('radio', { name: `Objekt ${fid} führt` })

/** The body of the merge request, parsed. */
function mergeBody(stub: ReturnType<typeof stubFetch>) {
  const request = stub.requests.find((entry) => entry.url.includes('/features/merge'))
  return JSON.parse(String(request?.init?.body))
}

describe('MergeDialog', () => {
  test('zeigt die Attribute, an denen die Wahl hängt', async () => {
    renderDialog(routesFor())

    // "Objekt 42 oder Objekt 43" is not a question anybody can answer.
    expect(await screen.findByText('Alte Landstraße')).toBeInTheDocument()
    expect(screen.getByText('Neue Landstraße')).toBeInTheDocument()
  })

  test('bleibt gesperrt, bis jemand ein führendes Objekt gewählt hat', async () => {
    renderDialog(routesFor())

    await screen.findByText('Alte Landstraße')

    // No default, on purpose: the contract has the client send `leadFid` precisely
    // because the user picked it, and the order of a selection is not a decision.
    expect(confirmButton()).toBeDisabled()

    await userEvent.click(leadRadio(42))

    expect(confirmButton()).toBeEnabled()
  })

  test('sendet leadFid und die rowVersion jedes Teils', async () => {
    const { stub, onDone } = renderDialog(
      routesFor([{ match: '/features/merge', body: { fid: 42, dataVersion: 13, featureCount: 1336 } }]),
    )

    await screen.findByText('Alte Landstraße')
    await userEvent.click(leadRadio(42))
    await userEvent.click(confirmButton())

    await waitFor(() => expect(onDone).toHaveBeenCalledWith(42))
    expect(mergeBody(stub)).toEqual({
      fids: [42, 43],
      leadFid: 42,
      rowVersions: { '42': '8241', '43': '8242' },
    })
  })

  test('sendet das gewählte führende Objekt, nicht das erste der Auswahl', async () => {
    const { stub } = renderDialog(
      routesFor([{ match: '/features/merge', body: { fid: 43, dataVersion: 13, featureCount: 1336 } }]),
    )

    await screen.findByText('Alte Landstraße')
    // 43 is the second row. Picking it has to reach the request, or the choice was
    // decoration and the first fid would decide which attributes survive.
    await userEvent.click(leadRadio(43))
    await userEvent.click(confirmButton())

    await waitFor(() => expect(mergeBody(stub).leadFid).toBe(43))
  })

  test('erklärt einen 409 als fremde Änderung und schreibt nichts fest', async () => {
    const { onDone } = renderDialog(
      routesFor([
        {
          match: '/features/merge',
          status: 409,
          body: { detail: 'Eine andere Stelle hat Objekt 43 zwischenzeitlich geändert' },
        },
      ]),
    )

    await screen.findByText('Alte Landstraße')
    await userEvent.click(leadRadio(42))
    await userEvent.click(confirmButton())

    expect(
      await screen.findByText(/Ein anderer Benutzer hat eines der Objekte inzwischen geändert/),
    ).toBeInTheDocument()
    expect(onDone).not.toHaveBeenCalled()
  })

  test('lehnt gemischte Geometriearten ab, bevor die Anfrage rausgeht', async () => {
    const stub = stubFetch([
      { match: '/features/42', body: FEATURE_42 },
      { match: '/features/43', body: feature(43, '8242', 'Neue Landstraße', 'LineString') },
    ])
    renderWithQueryClient(
      <MergeDialog
        layerId="l1"
        projectId="p1"
        fids={[42, 43]}
        fields={FIELDS}
        onCancel={vi.fn()}
        onDone={vi.fn()}
      />,
    )

    // Same wording the server would answer with, so the message reads the same
    // whichever side caught it (CONTRACT.md 12.2).
    expect(
      await screen.findByText('Nur Objekte derselben Geometrieart lassen sich zusammenführen.'),
    ).toBeInTheDocument()
    expect(confirmButton()).toBeDisabled()
    expect(stub.calls.some((url) => url.includes('/features/merge'))).toBe(false)
  })
})
