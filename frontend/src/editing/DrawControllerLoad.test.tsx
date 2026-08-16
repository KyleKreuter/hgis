import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, waitFor } from '@testing-library/react'
import { MapContext, type MapContextValue } from '@/map/MapContext'
import { countChanges, useEditing } from '@/state/editing'
import { renderWithQueryClient, stubFetch } from '@/test/render'
import type { ReactNode } from 'react'

/**
 * Was beim Laden eines bestückten Layers passiert -- und was dabei *nicht* passieren darf.
 *
 * terra-draw meldet jedes Objekt, das `addFeatures` bekommt, im selben Zug durch seinen
 * Änderungszähler. Der fragt dort, ob dieses Objekt geladen wurde, um eine wachsende Form
 * von einer fertigen zu unterscheiden. Wird die Antwort erst *nach* `addFeatures`
 * hinterlegt, sieht ein ganzer Layer aus wie lauter angefangene Zeichnungen -- und jeder
 * Wächter blockiert von der ersten Sekunde an.
 *
 * Der Ersatz für terra-draw unten tut deshalb genau eine Sache echt: er ruft beim
 * Hinzufügen den Änderungszähler auf, so wie die Bibliothek es tut. Alles andere ist
 * Beiwerk, damit der Controller überhaupt läuft.
 */

/** Handler, die der Controller bei terra-draw angemeldet hat. */
const handlers = new Map<string, (...args: unknown[]) => void>()
/** Alles, was gerade auf der Zeichenfläche liegt. */
let surface: { id: number; geometry: unknown; properties: Record<string, unknown> }[] = []

class FakeTerraDraw {
  start() {}
  stop() {}
  setMode() {}
  getSnapshot() {
    return surface
  }
  on(name: string, handler: (...args: unknown[]) => void) {
    handlers.set(name, handler)
  }
  removeFeatures(ids: number[]) {
    surface = surface.filter((feature) => !ids.includes(feature.id))
  }
  updateFeatureGeometry() {}
  addFeatures(features: { id: number; geometry: unknown; properties: Record<string, unknown> }[]) {
    surface = [...surface, ...features]
    // Der springende Punkt: terra-draw meldet das Hinzufügen sofort, noch bevor
    // `addFeatures` zurückkehrt.
    handlers.get('change')?.(features.map((feature) => feature.id), 'create')
    return features.map((feature) => ({ id: feature.id, valid: true }))
  }
}

vi.mock('terra-draw', () => ({
  TerraDraw: class {
    constructor() {
      return new FakeTerraDraw()
    }
  },
  TerraDrawPointMode: class {},
  TerraDrawLineStringMode: class {},
  TerraDrawPolygonMode: class {},
  TerraDrawSelectMode: class {},
}))

vi.mock('terra-draw-maplibre-gl-adapter', () => ({
  TerraDrawMapLibreGLAdapter: class {},
}))

const { DrawController } = await import('./DrawController')

const FLAECHE = {
  type: 'Polygon',
  coordinates: [[[9.98, 53.55], [9.99, 53.55], [9.99, 53.56], [9.98, 53.55]]],
}

/** Nur das, was der Controller von der Karte anfasst. */
function fakeMap() {
  return {
    getCenter: () => ({ lat: 53.55, lng: 9.98 }),
    getBounds: () => ({ getWest: () => 9.9, getSouth: () => 53.5, getEast: () => 10.1, getNorth: () => 53.6 }),
    project: ([lng, lat]: [number, number]) => ({ x: lng, y: lat }),
    on: () => {},
    off: () => {},
  }
}

function withMap(children: ReactNode) {
  const value = {
    mapRef: { current: fakeMap() },
    isLoaded: true,
    attribution: [],
  } as unknown as MapContextValue
  return <MapContext value={value}>{children}</MapContext>
}

describe('DrawController lädt einen bestückten Layer', () => {
  beforeEach(() => {
    handlers.clear()
    surface = []
    useEditing.getState().end()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('hält geladene Objekte nicht für angefangene Zeichnungen', async () => {
    stubFetch([
      {
        match: '/features',
        body: {
          features: [
            { fid: 1, rowVersion: '1', geometry: FLAECHE, properties: {} },
            { fid: 2, rowVersion: '1', geometry: FLAECHE, properties: {} },
          ],
          totalCount: 2,
        },
      },
    ])
    useEditing.getState().begin('layer-a')

    renderWithQueryClient(
      withMap(
        <DrawController
          layerId="layer-a"
          geometryType="MULTIPOLYGON"
          tool="polygon"
          onSelectFeature={vi.fn()}
          reloadNonce={0}
          snapEnabled={false}
          onSnapTarget={vi.fn()}
          onSnapUnavailable={vi.fn()}
          snapSourceLayerIds={[]}
        />,
      ),
    )

    // Die beiden Objekte sind auf der Zeichenfläche angekommen ...
    await waitFor(() => expect(surface).toHaveLength(2))
    // ... und keines davon zählt als angefangene Arbeit.
    expect(useEditing.getState().sketching).toBe(false)
    expect(countChanges(useEditing.getState().buffer)).toBe(0)
  })

  it('räumt die Sperre mit, wenn der Abgleich die Zeichenfläche leert', async () => {
    // „Verwerfen" schreibt nicht über den Änderungszähler, sondern über den Abgleich --
    // der umgeht ihn ausdrücklich. Ohne eine eigene Meldung von dort überlebte die Sperre
    // eine geleerte Zeichenfläche, und jeder Wächter blieb zu.
    stubFetch([
      {
        match: '/features',
        body: {
          features: [{ fid: 1, rowVersion: '1', geometry: FLAECHE, properties: {} }],
          totalCount: 1,
        },
      },
    ])
    useEditing.getState().begin('layer-a')

    renderWithQueryClient(
      withMap(
        <DrawController
          layerId="layer-a"
          geometryType="MULTIPOLYGON"
          tool="polygon"
          onSelectFeature={vi.fn()}
          reloadNonce={0}
          snapEnabled={false}
          onSnapTarget={vi.fn()}
          onSnapUnavailable={vi.fn()}
          snapSourceLayerIds={[]}
        />,
      ),
    )
    await waitFor(() => expect(surface).toHaveLength(1))

    // Eine angefangene Form: auf der Fläche, in keinem der beiden Verzeichnisse.
    act(() => {
      surface = [...surface, { id: -1, geometry: FLAECHE, properties: { mode: 'polygon' } }]
      handlers.get('change')?.([-1], 'create')
    })
    expect(useEditing.getState().sketching).toBe(true)

    act(() => useEditing.getState().reset())

    await waitFor(() => expect(useEditing.getState().sketching).toBe(false))
    // Und die Form ist auch wirklich weg, nicht nur die Sperre.
    expect(surface.map((feature) => feature.id)).toEqual([1])
  })
})
