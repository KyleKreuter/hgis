import { render, screen } from '@testing-library/react'
import { act } from 'react'
import { describe, expect, test } from 'vitest'
import { MapContext } from '../MapContext'
import { OSM_ATTRIBUTION } from '../basemap'
import { ScaleControl } from './ScaleControl'

/**
 * The bar's own width and label come from `computeScaleBar` (`scale.test.ts` covers
 * that maths); what is checked here is what this control adds on top -- whether it
 * shows the "Mitte" qualifier, and only once the map is actually tilted.
 */

type Handler = () => void

function fakeMap({ lat = 53.55, zoom = 15, pitch = 0 }: { lat?: number; zoom?: number; pitch?: number } = {}) {
  let currentPitch = pitch
  const handlers = new Map<string, Handler[]>()
  return {
    getCenter: () => ({ lng: 10, lat }),
    getZoom: () => zoom,
    getPitch: () => currentPitch,
    on: (event: string, fn: Handler) => {
      handlers.set(event, [...(handlers.get(event) ?? []), fn])
    },
    off: (event: string, fn: Handler) => {
      handlers.set(event, (handlers.get(event) ?? []).filter((h) => h !== fn))
    },
    listenerCount: () => [...handlers.values()].reduce((n, list) => n + list.length, 0),
    /** Simulates MapLibre firing `move` -- confirmed in the browser to fire for a pitch-only change too. */
    setPitchAndMove: (next: number) => {
      currentPitch = next
      for (const fn of handlers.get('move') ?? []) fn()
    },
  }
}

function renderScale(map: ReturnType<typeof fakeMap>) {
  return render(
    <MapContext
      value={{
        mapRef: { current: map as never },
        isLoaded: true,
        attribution: OSM_ATTRIBUTION,
      }}
    >
      <ScaleControl />
    </MapContext>,
  )
}

describe('ScaleControl', () => {
  test('zeigt bei flacher Karte keinen Hinweis auf die Kartenmitte', () => {
    renderScale(fakeMap({ pitch: 0 }))

    expect(screen.queryByText('(Mitte)', { exact: false })).toBeNull()
  })

  test('weist bei geneigter Karte auf die Kartenmitte hin', () => {
    renderScale(fakeMap({ pitch: 45 }))

    expect(screen.getByText('(Mitte)', { exact: false })).toBeTruthy()
  })

  test('folgt der Neigung, sobald die Karte kippt, ohne dass sich Breite oder Zoom ändern', () => {
    const map = fakeMap({ pitch: 0 })
    renderScale(map)
    expect(screen.queryByText('(Mitte)', { exact: false })).toBeNull()

    act(() => {
      map.setPitchAndMove(45)
    })

    expect(screen.getByText('(Mitte)', { exact: false })).toBeTruthy()

    act(() => {
      map.setPitchAndMove(0)
    })

    expect(screen.queryByText('(Mitte)', { exact: false })).toBeNull()
  })

  test('hängt seinen Beobachter beim Abbau wieder ab', () => {
    const map = fakeMap()
    const { unmount } = renderScale(map)
    expect(map.listenerCount()).toBeGreaterThan(0)

    unmount()

    expect(map.listenerCount()).toBe(0)
  })
})
