import { render } from '@testing-library/react'
import { act } from 'react'
import { afterEach, describe, expect, test } from 'vitest'
import { geodesicDistance, type LngLat } from '@/measurement/geodesy'
import { MapContext } from './MapContext'
import { OSM_ATTRIBUTION } from './basemap'
import { MapViewportTracker } from './MapViewportTracker'
import { useMapViewport } from './mapViewportStore'
import { metersPerPixel } from './scale'

/**
 * What `isPitchExpanded`'s own tests (`viewportBounds.test.ts`) cannot cover: whether
 * this component actually carries its hysteresis memory from one `moveend` to the next,
 * rather than reading `wasExpanded: false` every time (which would silently turn the
 * hysteresis back into the single threshold it replaced) or forgetting to write the
 * ref back (which would freeze it at whatever the first call decided).
 */

const WIDTH = 1000
const HEIGHT = 800
const CENTER: LngLat = [10, 53.55]
const ZOOM = 15

/** A bbox whose diagonal is `ratio` times a flat view's at `ZOOM`, north edge moved. */
function bboxAtRatio(ratio: number) {
  const mpp = metersPerPixel(CENTER[1], ZOOM)
  const latPerMeter = 1 / 111_320
  const lngPerMeter = 1 / (111_320 * Math.cos((CENTER[1] * Math.PI) / 180))
  const dLat = (HEIGHT / 2) * mpp * latPerMeter
  const dLng = (WIDTH / 2) * mpp * lngPerMeter
  const west = CENTER[0] - dLng
  const east = CENTER[0] + dLng
  const south = CENTER[1] - dLat

  const targetDiagonal = mpp * Math.hypot(WIDTH, HEIGHT) * ratio
  let tooClose = CENTER[1]
  let farEnough = CENTER[1] + 50
  for (let i = 0; i < 40; i += 1) {
    const mid = (tooClose + farEnough) / 2
    const diagonal = geodesicDistance([west, south], [east, mid])
    if (diagonal > targetDiagonal) farEnough = mid
    else tooClose = mid
  }
  return { getWest: () => west, getSouth: () => south, getEast: () => east, getNorth: () => farEnough }
}

type Handler = () => void

function fakeMap() {
  const handlers = new Map<string, Handler[]>()
  let bounds = bboxAtRatio(1)
  return {
    getBounds: () => bounds,
    getCanvas: () => ({ clientWidth: WIDTH, clientHeight: HEIGHT }),
    getCenter: () => ({ lng: CENTER[0], lat: CENTER[1] }),
    getZoom: () => ZOOM,
    on: (event: string, fn: Handler) => {
      handlers.set(event, [...(handlers.get(event) ?? []), fn])
    },
    off: (event: string, fn: Handler) => {
      handlers.set(event, (handlers.get(event) ?? []).filter((h) => h !== fn))
    },
    setRatio(ratio: number) {
      bounds = bboxAtRatio(ratio)
    },
    fireMoveend() {
      for (const fn of handlers.get('moveend') ?? []) fn()
    },
  }
}

function renderTracker(map: ReturnType<typeof fakeMap>) {
  return render(
    <MapContext value={{ mapRef: { current: map as never }, isLoaded: true, attribution: OSM_ATTRIBUTION }}>
      <MapViewportTracker />
    </MapContext>,
  )
}

describe('MapViewportTracker', () => {
  afterEach(() => {
    useMapViewport.setState({ bbox: null, zoom: null, pitchExpanded: false })
  })

  test('carries its hysteresis state from one moveend to the next, not just the first report', () => {
    const map = fakeMap()
    map.setRatio(1.65) // above the high threshold
    renderTracker(map) // the initial `report()` call, before any `moveend`
    expect(useMapViewport.getState().pitchExpanded).toBe(true)

    act(() => {
      map.setRatio(1.5) // inside the band -- must stay on, not fall back to the old single 1.5 threshold
      map.fireMoveend()
    })
    expect(useMapViewport.getState().pitchExpanded).toBe(true)

    act(() => {
      map.setRatio(1.38) // below the low threshold
      map.fireMoveend()
    })
    expect(useMapViewport.getState().pitchExpanded).toBe(false)

    act(() => {
      map.setRatio(1.5) // inside the band again -- must stay off now, the mirror of the first check
      map.fireMoveend()
    })
    expect(useMapViewport.getState().pitchExpanded).toBe(false)
  })

  test('remounting at an unchanged ratio inside the band does not flip an already-on note off', () => {
    // Reproduces the finding exactly: pitch stays at a ratio inside 1.4-1.6 the whole
    // time -- no user action -- but the component (and with it, the ref) gets torn down
    // and rebuilt, the way an error boundary or a future conditional mount could.
    const map = fakeMap()
    map.setRatio(1.65) // establish "on" against the high threshold
    const first = renderTracker(map)
    expect(useMapViewport.getState().pitchExpanded).toBe(true)

    first.unmount()
    map.setRatio(1.5) // still inside the band -- an unseeded ref would compare this to
    // the high threshold again on the next mount and miss it, turning the note off
    // without any change in pitch at all.
    renderTracker(map)

    expect(useMapViewport.getState().pitchExpanded).toBe(true)
  })
})
