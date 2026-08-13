import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { MapContext } from '../MapContext'
import { OSM_ATTRIBUTION } from '../basemap'
import { ResetViewControl } from './ResetViewControl'

const HAMBURG: [number, number, number, number] = [9.73, 53.39, 10.33, 53.74]

function renderReset(extent: [number, number, number, number] | null) {
  const map = { fitBounds: vi.fn() }
  render(
    <MapContext
      value={{ mapRef: { current: map as never }, isLoaded: true, attribution: OSM_ATTRIBUTION }}
    >
      <ResetViewControl extent={extent} />
    </MapContext>,
  )
  return map
}

describe('ResetViewControl', () => {
  test('fliegt auf die Ausdehnung des Projekts', async () => {
    const map = renderReset(HAMBURG)

    await userEvent.click(screen.getByRole('button'))

    expect(map.fitBounds).toHaveBeenCalledWith(
      [
        [9.73, 53.39],
        [10.33, 53.74],
      ],
      expect.anything(),
    )
  })

  test('richtet dabei auch Drehung und Neigung zurück', async () => {
    // Sonst führte der Weg zurück zur Gesamtansicht auf eine schräge Gesamtansicht.
    const map = renderReset(HAMBURG)

    await userEvent.click(screen.getByRole('button'))

    expect(map.fitBounds).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ bearing: 0, pitch: 0 }),
    )
  })

  test('begrenzt den Zoom, damit ein Projekt aus einem einzigen Punkt nicht ins Leere springt', async () => {
    const map = renderReset([9.99, 53.55, 9.99, 53.55])

    await userEvent.click(screen.getByRole('button'))

    expect(map.fitBounds).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ maxZoom: 17 }),
    )
  })

  test('ist ohne Ausdehnung gesperrt und sagt warum', async () => {
    const map = renderReset(null)
    const button = screen.getByRole('button')

    expect(button.hasAttribute('disabled')).toBe(true)
    expect(button.getAttribute('title')).toContain('noch keine Daten')

    await userEvent.click(button)
    expect(map.fitBounds).not.toHaveBeenCalled()
  })
})
