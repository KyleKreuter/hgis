import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useEditing } from '@/state/editing'
import { EditToolbar } from './EditToolbar'

/**
 * The running toolbar: how one gets out of the drawing mode, and what the buttons in it
 * are allowed to do while the buffer is empty.
 *
 * Rendered against the real editing store, because the pending count the toolbar reacts
 * to is read straight off it -- a stubbed store would only prove that the component reads
 * the selector it was written to read.
 */

const POINT: GeoJSON.Point = { type: 'Point', coordinates: [10, 53.5] }

function renderActive(props: Partial<Parameters<typeof EditToolbar>[0]> = {}) {
  return render(
    <EditToolbar
      active
      geometryType="MULTIPOINT"
      tool="select"
      onToolChange={vi.fn()}
      onStart={vi.fn()}
      onSave={vi.fn()}
      onDiscard={vi.fn()}
      onLeave={vi.fn()}
      onDelete={vi.fn()}
      canDelete={false}
      isSaving={false}
      canEdit
      snapEnabled
      onToggleSnap={vi.fn()}
      {...props}
    />,
  )
}

const leaveButton = () => screen.getByRole('button', { name: 'Zeichenmodus verlassen' })
const discardButton = () => screen.getByRole('button', { name: 'Verwerfen' })

describe('EditToolbar', () => {
  afterEach(() => {
    useEditing.getState().end()
  })

  test('bietet einen Ausgang aus dem Zeichenmodus', async () => {
    const onLeave = vi.fn()
    renderActive({ onLeave })

    await userEvent.click(leaveButton())

    expect(onLeave).toHaveBeenCalledOnce()
  })

  test('lässt den Ausgang auch mit ungespeicherten Änderungen zu', async () => {
    // Die Rückfrage stellt die Route, nicht die Werkzeugleiste. Ein hier gesperrter Knopf
    // hätte genau die Sackgasse ergeben, aus der man nur noch über Speichern herauskam.
    useEditing.getState().begin('l1')
    useEditing.getState().addFeature(POINT)
    const onLeave = vi.fn()
    renderActive({ onLeave })

    await userEvent.click(leaveButton())

    expect(onLeave).toHaveBeenCalledOnce()
  })

  test('sperrt Verwerfen, solange es nichts zu verwerfen gibt', () => {
    renderActive()

    expect(discardButton()).toBeDisabled()
  })

  test('gibt Verwerfen frei, sobald der Puffer etwas hält', async () => {
    useEditing.getState().begin('l1')
    useEditing.getState().addFeature(POINT)
    const onDiscard = vi.fn()
    renderActive({ onDiscard })

    await userEvent.click(discardButton())

    expect(onDiscard).toHaveBeenCalledOnce()
  })

  test('zeichnet das Punktwerkzeug als Punkt', () => {
    // Vorher war es ein waagerechter Strich -- dasselbe Zeichen, das der Zoomregler für
    // "kleiner" verwendet.
    const { container } = renderActive()

    const point = screen.getByRole('button', { name: 'Punkt zeichnen' })
    expect(point.querySelector('circle')).not.toBeNull()
    expect(container.querySelector('svg path[d="M5 12h14"]')).toBeNull()
  })
})
