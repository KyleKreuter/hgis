import { useEffect, useMemo, useRef } from 'react'
import type { MapMouseEvent } from 'maplibre-gl'
import { useMap } from '@/map/MapContext'
import { raiseOverlays, SPLIT_LINE_LAYER_PREFIX } from '@/map/overlays'
import { isSecondClick, wasSecondClickPlaced, type ClickRecord } from '@/measurement/doubleClick'
import { suspendHandler } from '@/measurement/interaction'
import { splitLineFeatures, splitLineKeyEventAction, toSplitLine } from './splitLine'
import { useStructure } from './structureStore'

/** Also the id prefix `overlays` recognises the sketch by. */
const SOURCE_ID = SPLIT_LINE_LAYER_PREFIX
const LINE_LAYER = `${SOURCE_ID}-line`
const PENDING_LAYER = `${SOURCE_ID}-pending`
const VERTEX_LAYER = `${SOURCE_ID}-vertex`
const LAYER_IDS = [LINE_LAYER, PENDING_LAYER, VERTEX_LAYER]

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] }

/**
 * The cut, in the same red the delete actions use -- this line destroys an object as it
 * stands, and the sketch says so before the confirmation does.
 */
const CUT_COLOR = '#dc2626'

/**
 * Renders nothing into React. Collects the clicks the cutting line is made of and draws
 * it on the map.
 *
 * Mounted only while the split tool is armed, which makes the teardown the off switch:
 * the layers go, the pointer goes back to what it was, and double-click zoom -- taken
 * away below, because a double-click has to be able to end the line -- goes back to
 * whatever it was before, which is not the same as "on". The same shape as
 * `MeasurementLayer`, and deliberately so: two tools that collect clicks on the map
 * should not behave differently while doing it.
 */
export function SplitLineTool() {
  const { mapRef, isLoaded } = useMap()
  const phase = useStructure((state) => state.phase)
  const points = phase.type === 'drawing' ? phase.points : null
  /** The last click, and whether it actually became a vertex -- see `doubleClick`. */
  const lastClick = useRef<ClickRecord | null>(null)
  /**
   * The rubber band's far end. Held in a ref and pushed straight into the source rather
   * than kept in state: it changes with every pointer move, and re-rendering the
   * workspace at mouse-move rate for a dashed line would be absurd.
   */
  const cursor = useRef<[number, number] | null>(null)

  const drawn = useMemo(
    () => (points ? splitLineFeatures({ points, cursor: cursor.current }) : EMPTY),
    [points],
  )

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    if (!map.getSource(SOURCE_ID)) {
      map.addSource(SOURCE_ID, { type: 'geojson', data: EMPTY })
      map.addLayer({
        id: LINE_LAYER,
        type: 'line',
        source: SOURCE_ID,
        filter: ['==', ['get', 'role'], 'line'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': CUT_COLOR, 'line-width': 2 },
      })
      map.addLayer({
        id: PENDING_LAYER,
        type: 'line',
        source: SOURCE_ID,
        filter: ['==', ['get', 'role'], 'pending'],
        paint: { 'line-color': CUT_COLOR, 'line-width': 1.5, 'line-dasharray': [2, 2] },
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
          'circle-stroke-color': CUT_COLOR,
        },
      })
    }

    // The sketch belongs above the data, the selection highlight and every other
    // overlay -- see `map/overlays`, which keeps it there when the catalog is reconciled.
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
    source?.setData(drawn)
  }, [mapRef, isLoaded, drawn])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    const canvas = map.getCanvas()
    const previousCursor = canvas.style.cursor
    canvas.style.cursor = 'crosshair'
    // Otherwise the double-click that ends the line also zooms the map in under it.
    // Restored to what it was, never simply switched back on -- see `suspendHandler`.
    const restoreDoubleClickZoom = suspendHandler(map.doubleClickZoom)
    lastClick.current = null
    cursor.current = null

    /** Pushes the sketch without going through React -- see `cursor`. */
    function redraw() {
      const source = mapRef.current?.getSource(SOURCE_ID) as
        | { setData: (data: GeoJSON.FeatureCollection) => void }
        | undefined
      const current = useStructure.getState().phase
      source?.setData(
        current.type === 'drawing'
          ? splitLineFeatures({ points: current.points, cursor: cursor.current })
          : EMPTY,
      )
    }

    function place(event: MapMouseEvent) {
      const click = { x: event.point.x, y: event.point.y, time: event.originalEvent.timeStamp }
      const second = isSecondClick(lastClick.current, click)

      lastClick.current = { ...click, placed: !second }
      if (second) return

      useStructure.getState().addPoint([event.lngLat.lng, event.lngLat.lat])
    }

    function follow(event: MapMouseEvent) {
      cursor.current = [event.lngLat.lng, event.lngLat.lat]
      redraw()
    }

    function leave() {
      cursor.current = null
      redraw()
    }

    function end(event: MapMouseEvent) {
      // Both clicks of the double-click have already been through `place`, where the
      // window is deliberately short. A leisurely double-click therefore gets its second
      // click placed; here the double-click is a fact rather than a guess, and that
      // vertex is taken back again.
      if (wasSecondClickPlaced(lastClick.current, event.point)) useStructure.getState().undoPoint()
      lastClick.current = null
      finish()
    }

    function finish() {
      const current = useStructure.getState().phase
      if (current.type !== 'drawing') return
      const line = toSplitLine(current.points)
      // Below two points there is no line to send. Left standing rather than refused:
      // the user is still drawing, and a complaint at this moment would be noise.
      if (!line) return
      cursor.current = null
      useStructure.getState().finishLine(line)
    }

    function takeBack() {
      // The browser's own menu stays away by itself: MapLibre suppresses it as soon as
      // anything listens for `contextmenu`.
      useStructure.getState().undoPoint()
    }

    function handleKey(event: KeyboardEvent) {
      const current = useStructure.getState()
      const pointCount = current.phase.type === 'drawing' ? current.phase.points.length : 0
      const action = splitLineKeyEventAction(event, pointCount)
      if (!action) return

      // Claimed only once it is certain the press was ours -- Enter and Backspace have
      // meanings elsewhere (submit, navigate back) that must survive untouched.
      event.preventDefault()
      if (action === 'clear') current.clearPoints()
      else if (action === 'cancel') current.cancel()
      else if (action === 'finish') finish()
      else current.undoPoint()
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
      cursor.current = null
      // Only if it is still ours: another tool may have set the cursor it wants by now.
      if (canvas.style.cursor === 'crosshair') canvas.style.cursor = previousCursor
      restoreDoubleClickZoom()
    }
  }, [mapRef, isLoaded])

  return null
}
