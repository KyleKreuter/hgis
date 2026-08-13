import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, test } from 'vitest'
import { renderWithQueryClient } from '@/test/render'
import type { GeometryType } from '@/api/layers'
import { useSelection } from '@/state/selection'
import { StructureToolbar } from './StructureToolbar'
import { useStructure } from './structureStore'

/**
 * The two buttons, rendered against the real selection store.
 *
 * What is under test here is only what the toolbar decides for itself: whether the tools
 * exist on this layer, and whether they may be used right now. The wording of every
 * refusal is checked in `structureTools.test.ts` -- a tooltip only opens on hover, and a
 * disabled button never opens one at all.
 */

const LAYER = 'l1'

interface Options {
  geometryType?: GeometryType
  pendingChanges?: number
  drawingActive?: boolean
}

function renderToolbar({
  geometryType = 'MULTIPOLYGON',
  pendingChanges = 0,
  drawingActive = false,
}: Options = {}) {
  return renderWithQueryClient(
    <StructureToolbar
      layerId={LAYER}
      geometryType={geometryType}
      pendingChanges={pendingChanges}
      drawingActive={drawingActive}
    />,
  )
}

const splitButton = () => screen.queryByRole('button', { name: 'Objekt teilen' })
const mergeButton = () => screen.queryByRole('button', { name: 'Objekte zusammenführen' })

describe('StructureToolbar', () => {
  beforeEach(() => {
    useSelection.getState().clear()
    useStructure.getState().cancel()
  })

  test('zeigt auf einem Punktlayer kein Werkzeug', () => {
    useSelection.getState().select(LAYER, [42])

    renderToolbar({ geometryType: 'MULTIPOINT' })

    // Hidden, not disabled: a greyed-out scissors would suggest that cutting a point is
    // a thing that exists and is merely unavailable right now (CONTRACT.md 12).
    expect(splitButton()).toBeNull()
    expect(mergeButton()).toBeNull()
  })

  test('sperrt beide Werkzeuge, solange Änderungen offen sind', () => {
    useSelection.getState().select(LAYER, [42, 43])

    renderToolbar({ pendingChanges: 2 })

    // The rule CONTRACT.md 12 states outright: both write immediately, so a filled
    // buffer would be stale the moment they did.
    expect(splitButton()).toBeDisabled()
    expect(mergeButton()).toBeDisabled()
  })

  test('sperrt beide Werkzeuge im Zeichenmodus', () => {
    useSelection.getState().select(LAYER, [42, 43])

    renderToolbar({ drawingActive: true })

    expect(splitButton()).toBeDisabled()
    expect(mergeButton()).toBeDisabled()
  })

  test('gibt Teilen bei genau einem ausgewählten Objekt frei', () => {
    useSelection.getState().select(LAYER, [42])

    renderToolbar()

    expect(splitButton()).toBeEnabled()
    // Two is the smallest merge there is.
    expect(mergeButton()).toBeDisabled()
  })

  test('gibt Zusammenführen ab zwei ausgewählten Objekten frei', () => {
    useSelection.getState().select(LAYER, [42, 43])

    renderToolbar()

    expect(mergeButton()).toBeEnabled()
    // A split cuts one object, and which of the two it would be is not a question the
    // toolbar may answer for the user.
    expect(splitButton()).toBeDisabled()
  })

  test('zählt eine Auswahl aus einem anderen Layer nicht mit', () => {
    // A fid only identifies a row within its own layer -- fid 42 elsewhere is a
    // different object entirely.
    useSelection.getState().select('l2', [42, 43])

    renderToolbar()

    expect(splitButton()).toBeDisabled()
    expect(mergeButton()).toBeDisabled()
  })

  test('zeigt kein Werkzeug ohne aktiven Layer', () => {
    renderWithQueryClient(
      <StructureToolbar layerId={null} pendingChanges={0} drawingActive={false} />,
    )

    expect(splitButton()).toBeNull()
    expect(mergeButton()).toBeNull()
  })
})
