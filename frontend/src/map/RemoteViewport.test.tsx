import { render } from '@testing-library/react'
import { act } from 'react'
import { describe, expect, test } from 'vitest'
import { MapContext } from './MapContext'
import { OSM_ATTRIBUTION } from './basemap'
import { RemoteViewport, type ViewportRequest } from './RemoteViewport'

/**
 * TASKS.md Aufgabe 9's own two conditions: `easeTo`, never `jumpTo` (checked by capturing
 * the call itself, not just the resulting position), and not applied mid-gesture (checked
 * by driving the same `movestart`/`moveend` sequence a real drag or scroll-zoom produces).
 *
 * The fake mirrors `Evented.once`'s real removal semantics from `maplibre-gl` itself
 * (`node_modules/maplibre-gl/src/util/evented.ts`): `once` stores the listener by
 * reference, and `off` with that same reference removes it from either list -- which is
 * exactly what lets a superseded, still-waiting `easeTo` be cancelled cleanly.
 */

type Handler = (data?: unknown) => void

interface EaseToCall {
  center: [number, number]
  zoom: number
  duration?: number
}

function fakeMap() {
  const listeners = new Map<string, Handler[]>()
  const onceListeners = new Map<string, Handler[]>()
  const easeToCalls: EaseToCall[] = []
  return {
    easeTo: (opts: EaseToCall) => {
      easeToCalls.push(opts)
    },
    on: (event: string, fn: Handler) => {
      listeners.set(event, [...(listeners.get(event) ?? []), fn])
    },
    once: (event: string, fn: Handler) => {
      onceListeners.set(event, [...(onceListeners.get(event) ?? []), fn])
    },
    off: (event: string, fn: Handler) => {
      listeners.set(event, (listeners.get(event) ?? []).filter((h) => h !== fn))
      onceListeners.set(event, (onceListeners.get(event) ?? []).filter((h) => h !== fn))
    },
    fire(event: string, data?: unknown) {
      for (const fn of listeners.get(event) ?? []) fn(data)
      const once = onceListeners.get(event) ?? []
      onceListeners.set(event, [])
      for (const fn of once) fn(data)
    },
    easeToCalls,
  }
}

function contextFor(map: ReturnType<typeof fakeMap>) {
  return { mapRef: { current: map as never }, isLoaded: true, attribution: OSM_ATTRIBUTION }
}

function renderRemote(map: ReturnType<typeof fakeMap>, request: ViewportRequest | null) {
  return render(
    <MapContext value={contextFor(map)}>
      <RemoteViewport request={request} />
    </MapContext>,
  )
}

const REQUEST: ViewportRequest = { center: [10, 53.55], zoom: 12, nonce: 1 }

describe('RemoteViewport', () => {
  test('wendet eine Anfrage sofort an, wenn niemand gerade die Karte bewegt', () => {
    const map = fakeMap()
    renderRemote(map, REQUEST)

    expect(map.easeToCalls).toHaveLength(1)
    expect(map.easeToCalls[0]).toMatchObject({ center: [10, 53.55], zoom: 12 })
    // Bedingung 1 aus TASKS.md Aufgabe 9: easeTo, nicht jumpTo -- geprüft daran, dass
    // überhaupt eine Dauer gesetzt ist, statt eines Sprungs ohne Übergang.
    expect(map.easeToCalls[0].duration).toBeGreaterThan(0)
  })

  test('tut nichts ohne Anfrage', () => {
    const map = fakeMap()
    renderRemote(map, null)

    expect(map.easeToCalls).toEqual([])
  })

  test('wartet mit einer Anfrage, bis eine laufende menschliche Geste endet', () => {
    const map = fakeMap()
    const { rerender } = renderRemote(map, null)

    act(() => {
      map.fire('movestart', { originalEvent: {} })
    })

    rerender(
      <MapContext value={contextFor(map)}>
        <RemoteViewport request={REQUEST} />
      </MapContext>,
    )
    expect(map.easeToCalls).toEqual([])

    act(() => {
      map.fire('moveend')
    })
    expect(map.easeToCalls).toHaveLength(1)
    expect(map.easeToCalls[0]).toMatchObject({ center: [10, 53.55], zoom: 12 })
  })

  test('wendet eine Anfrage sofort an, wenn die laufende Bewegung nicht vom Menschen ausging', () => {
    // Kein `originalEvent` -- eine programmatische Bewegung, etwa dieser Komponente
    // eigener vorheriger easeTo-Aufruf, darf die nächste Anfrage nicht aufhalten.
    const map = fakeMap()
    const { rerender } = renderRemote(map, null)

    act(() => {
      map.fire('movestart', {})
    })

    rerender(
      <MapContext value={contextFor(map)}>
        <RemoteViewport request={REQUEST} />
      </MapContext>,
    )

    expect(map.easeToCalls).toHaveLength(1)
  })

  test('ersetzt ein wartendes Ziel durch ein neueres, statt beide anzuwenden', () => {
    const map = fakeMap()
    const { rerender } = renderRemote(map, null)

    act(() => {
      map.fire('movestart', { originalEvent: {} })
    })

    rerender(
      <MapContext value={contextFor(map)}>
        <RemoteViewport request={{ center: [1, 1], zoom: 5, nonce: 1 }} />
      </MapContext>,
    )
    rerender(
      <MapContext value={contextFor(map)}>
        <RemoteViewport request={{ center: [2, 2], zoom: 8, nonce: 2 }} />
      </MapContext>,
    )

    act(() => {
      map.fire('moveend')
    })

    expect(map.easeToCalls).toHaveLength(1)
    expect(map.easeToCalls[0]).toMatchObject({ center: [2, 2], zoom: 8 })
  })

  test('gilt sofort, nachdem eine vorherige Geste bereits losgelassen wurde', () => {
    const map = fakeMap()
    const { rerender } = renderRemote(map, null)

    act(() => {
      map.fire('movestart', { originalEvent: {} })
      map.fire('moveend')
    })

    rerender(
      <MapContext value={contextFor(map)}>
        <RemoteViewport request={REQUEST} />
      </MapContext>,
    )

    expect(map.easeToCalls).toHaveLength(1)
  })
})
