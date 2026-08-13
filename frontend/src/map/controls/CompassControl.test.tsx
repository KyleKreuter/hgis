import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { MapContext } from '../MapContext'
import { OSM_ATTRIBUTION } from '../basemap'
import { CompassControl } from './CompassControl'

/**
 * The needle itself needs a real map to turn under it; what is checked here is what the
 * control decides -- how it names itself, when it draws the tilt bar, and that clicking
 * it asks for both angles back, not just north.
 */

type Handler = () => void

function fakeMap({ bearing = 0, pitch = 0 }: { bearing?: number; pitch?: number } = {}) {
  const handlers = new Map<string, Handler[]>()
  return {
    easeTo: vi.fn(),
    getBearing: () => bearing,
    getPitch: () => pitch,
    getMaxPitch: () => 60,
    on: (event: string, fn: Handler) => {
      handlers.set(event, [...(handlers.get(event) ?? []), fn])
    },
    off: (event: string, fn: Handler) => {
      handlers.set(event, (handlers.get(event) ?? []).filter((h) => h !== fn))
    },
    /** How many listeners survive after unmount -- the leak this control could cause. */
    listenerCount: () => [...handlers.values()].reduce((n, list) => n + list.length, 0),
  }
}

function renderCompass(map: ReturnType<typeof fakeMap>) {
  return render(
    <MapContext
      value={{
        mapRef: { current: map as never },
        isLoaded: true,
        attribution: OSM_ATTRIBUTION,
      }}
    >
      <CompassControl />
    </MapContext>,
  )
}

describe('CompassControl', () => {
  test('heißt bei flacher Karte nur nach Norden', () => {
    renderCompass(fakeMap())

    expect(screen.getByRole('button', { name: 'Norden oben' })).toBeTruthy()
  })

  test('nennt die Neigung, sobald die Karte geneigt ist', () => {
    renderCompass(fakeMap({ bearing: 35, pitch: 30 }))

    expect(screen.getByRole('button', { name: 'Norden oben, Neigung zurücksetzen' })).toBeTruthy()
  })

  test('zeigt den Neigungsbalken nur bei geneigter Karte', () => {
    const { unmount } = renderCompass(fakeMap({ bearing: 35, pitch: 0 }))
    expect(screen.queryByTestId('pitch-bar')).toBeNull()
    unmount()

    renderCompass(fakeMap({ bearing: 0, pitch: 30 }))
    expect(screen.getByTestId('pitch-bar')).toBeTruthy()
  })

  test('setzt beim Klick beide Winkel zurück, nicht nur die Drehung', async () => {
    const map = fakeMap({ bearing: 35, pitch: 30 })
    renderCompass(map)

    await userEvent.click(screen.getByRole('button'))

    expect(map.easeTo).toHaveBeenCalledWith(expect.objectContaining({ bearing: 0, pitch: 0 }))
  })

  test('bleibt auch dann sichtbar, wenn nichts zurückzusetzen ist', () => {
    // Ein Knopf, der erscheint und verschwindet, verschöbe die Zoomknöpfe unter dem Zeiger.
    renderCompass(fakeMap())

    expect(screen.getByRole('button')).toBeTruthy()
  })

  test('hängt seine Beobachter beim Abbau wieder ab', () => {
    const map = fakeMap()
    const { unmount } = renderCompass(map)
    expect(map.listenerCount()).toBeGreaterThan(0)

    unmount()

    expect(map.listenerCount()).toBe(0)
  })
})
