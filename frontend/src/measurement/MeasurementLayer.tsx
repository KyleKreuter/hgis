import { useEffect, useMemo, useRef } from 'react'
import type { MapMouseEvent } from 'maplibre-gl'
import { useMap } from '@/map/MapContext'
import { MEASUREMENT_LAYER_PREFIX, raiseOverlays } from '@/map/overlays'
import { isSecondClick, wasSecondClickPlaced, type ClickRecord } from './doubleClick'
import { suspendHandler } from './interaction'
import { measurementKeyEventAction } from './keyboard'
import { sketchFeatures } from './session'
import { useMeasurement } from './store'

/** Also the id prefix `overlays` recognises the sketch by. */
const SOURCE_ID = MEASUREMENT_LAYER_PREFIX
const FILL_LAYER = `${SOURCE_ID}-fill`
const LINE_LAYER = `${SOURCE_ID}-line`
const PENDING_LAYER = `${SOURCE_ID}-pending`
const VERTEX_LAYER = `${SOURCE_ID}-vertex`
const LAYER_IDS = [FILL_LAYER, LINE_LAYER, PENDING_LAYER, VERTEX_LAYER]

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] }

/**
 * Renders nothing into React. Draws the sketch on the map and collects the clicks it
 * is made of.
 *
 * Mounted only while a measuring mode is on, which is what makes the teardown the
 * off switch: the layers go, the pointer goes back to a grab hand, and double-click
 * zoom -- taken away below, because a double-click has to be able to end a sketch --
 * goes back to whatever it was before, which is not the same as "on".
 */
export function MeasurementLayer() {
  const { mapRef, isLoaded } = useMap()
  const mode = useMeasurement((state) => state.mode)
  const points = useMeasurement((state) => state.points)
  const cursor = useMeasurement((state) => state.cursor)
  const finished = useMeasurement((state) => state.finished)
  /** The last click, and whether it actually became a vertex -- see `doubleClick`. */
  const lastClick = useRef<ClickRecord | null>(null)

  const features = useMemo(
    () => sketchFeatures({ mode, points, cursor, finished }),
    [mode, points, cursor, finished],
  )

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    if (!map.getSource(SOURCE_ID)) {
      map.addSource(SOURCE_ID, { type: 'geojson', data: EMPTY })
      map.addLayer({
        id: FILL_LAYER,
        type: 'fill',
        source: SOURCE_ID,
        filter: ['==', ['get', 'role'], 'area'],
        paint: { 'fill-color': '#0f172a', 'fill-opacity': 0.12 },
      })
      map.addLayer({
        id: LINE_LAYER,
        type: 'line',
        source: SOURCE_ID,
        filter: ['==', ['get', 'role'], 'line'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#0f172a', 'line-width': 2 },
      })
      map.addLayer({
        id: PENDING_LAYER,
        type: 'line',
        source: SOURCE_ID,
        filter: ['==', ['get', 'role'], 'pending'],
        paint: { 'line-color': '#0f172a', 'line-width': 1.5, 'line-dasharray': [2, 2] },
      })
      map.addLayer({
        id: VERTEX_LAYER,
        type: 'circle',
        source: SOURCE_ID,
        filter: ['==', ['get', 'role'], 'vertex'],
        paint: {
          'circle-radius': 3.5,
          'circle-color': '#fafafa',
          'circle-stroke-width': 2,
          'circle-stroke-color': '#0f172a',
        },
      })
    }

    // The sketch belongs above the data and above the selection highlight, and has to
    // stay there when the catalog is reconciled -- see `map/overlays`.
    raiseOverlays(map)

    return () => {
      // Guarded throughout: the map may already be gone (route change, StrictMode
      // remount), and a teardown that throws leaves the rest of it undone.
      try {
        for (const id of LAYER_IDS) {
          if (map.getLayer(id)) map.removeLayer(id)
        }
        if (map.getSource(SOURCE_ID)) map.removeSource(SOURCE_ID)
      } catch {
        // Style already disposed; nothing left to remove.
      }
    }
  }, [mapRef, isLoaded])

  useEffect(() => {
    const source = mapRef.current?.getSource(SOURCE_ID) as
      | { setData: (data: GeoJSON.FeatureCollection) => void }
      | undefined
    source?.setData(features)
  }, [mapRef, isLoaded, features])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded || mode === null) return

    const store = useMeasurement.getState()
    const canvas = map.getCanvas()
    const previousCursor = canvas.style.cursor
    canvas.style.cursor = 'crosshair'
    // Otherwise the double-click that ends a sketch also zooms the map in under it.
    // Restored to what it was, never simply switched back on -- the drawing tool
    // disables the very same handler, and re-arming it there was a real bug.
    const restoreDoubleClickZoom = suspendHandler(map.doubleClickZoom)
    // A mode change starts a new sketch; the click that ended the previous one must
    // not count towards a double-click in the new one.
    lastClick.current = null

    function place(event: MapMouseEvent) {
      const click = { x: event.point.x, y: event.point.y, time: event.originalEvent.timeStamp }
      const second = isSecondClick(lastClick.current, click)

      lastClick.current = { ...click, placed: !second }
      if (second) return

      store.addVertex([event.lngLat.lng, event.lngLat.lat])
    }

    function follow(event: MapMouseEvent) {
      store.moveCursor([event.lngLat.lng, event.lngLat.lat])
    }

    function leave() {
      store.moveCursor(null)
    }

    function end(event: MapMouseEvent) {
      // Both clicks of the double-click have already been through `place`, where the
      // window is deliberately short. A leisurely double-click therefore gets its
      // second click placed; here the double-click is a fact rather than a guess, and
      // that vertex is taken back again.
      if (wasSecondClickPlaced(lastClick.current, event.point)) store.undoVertex()
      lastClick.current = null
      store.finish()
    }

    function takeBack() {
      // The browser's own menu stays away by itself: MapLibre suppresses it as soon as
      // anything listens for `contextmenu`.
      store.undoVertex()
    }

    function handleKey(event: KeyboardEvent) {
      const current = useMeasurement.getState()
      // Only inside the map: this listener sits on `window` and would otherwise
      // swallow Enter on a focused button, Escape in a dialog and Backspace in a text
      // field. See `keyboard`.
      const action = measurementKeyEventAction(event, current.points.length > 0)
      if (!action) return

      // Claimed only once it is certain the press was ours -- Enter and Backspace have
      // meanings elsewhere (submit, navigate back) that must survive untouched.
      event.preventDefault()
      if (action === 'clear') current.clear()
      else if (action === 'exit') current.exit()
      else if (action === 'finish') current.finish()
      else current.undoVertex()
    }

    map.on('click', place)
    map.on('mousemove', follow)
    map.on('mouseout', leave)
    map.on('dblclick', end)
    map.on('contextmenu', takeBack)
    window.addEventListener('keydown', handleKey)

    return () => {
      map.off('click', place)
      map.off('mousemove', follow)
      map.off('mouseout', leave)
      map.off('dblclick', end)
      map.off('contextmenu', takeBack)
      window.removeEventListener('keydown', handleKey)
      lastClick.current = null
      // Only if it is still ours. Starting to edit ends the measurement, and by the
      // time this runs the drawing tool has already set the cursor it wants.
      if (canvas.style.cursor === 'crosshair') canvas.style.cursor = previousCursor
      restoreDoubleClickZoom()
    }
  }, [mapRef, isLoaded, mode])

  return null
}
