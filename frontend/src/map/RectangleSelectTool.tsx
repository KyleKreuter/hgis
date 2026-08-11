import { useEffect, useRef, useState } from 'react'
import type { MapMouseEvent } from 'maplibre-gl'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { fetchFeaturePage } from '@/api/features'
import { formatCount } from '@/lib/format'
import { suspendHandler } from '@/measurement/interaction'
import { useSelection, type SelectionMode } from '@/state/selection'
import { useMap } from './MapContext'
import { RECTANGLE_SELECT_LAYER_PREFIX, raiseOverlays } from './overlays'
import { RectangleSelectConfirmDialog } from './RectangleSelectConfirmDialog'
import { bboxFromCorners, isMeaningfulDrag, rectanglePolygon, type Bbox } from './rectangleSelectGeometry'
import { modifierSelectionMode } from './rectangleSelectMode'
import { collectAllFids, exceedsMaximum, needsConfirmation, RECTANGLE_SELECT_MAX } from './rectangleSelectPaging'
import { useRectangleSelect } from './rectangleSelectStore'

interface RectangleSelectToolProps {
  layerId: string
}

const SOURCE_ID = RECTANGLE_SELECT_LAYER_PREFIX
const FILL_LAYER = `${SOURCE_ID}-fill`
const LINE_LAYER = `${SOURCE_ID}-line`
const LAYER_IDS = [FILL_LAYER, LINE_LAYER]
const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] }

/** Below this many screen pixels, a drag is treated as a stray click -- see `isMeaningfulDrag`. */
const DRAG_THRESHOLD_PX = 3

const GENERIC_ERROR = 'Auswahl konnte nicht geladen werden'

type Phase =
  | { type: 'idle' }
  | { type: 'dragging'; bbox: Bbox }
  | { type: 'resolving'; bbox: Bbox; mode: SelectionMode }
  | { type: 'confirm'; bbox: Bbox; mode: SelectionMode; totalCount: number }

const IDLE: Phase = { type: 'idle' }

function bboxOf(phase: Phase): Bbox | null {
  return phase.type === 'idle' ? null : phase.bbox
}

function toFeatureCollection(bbox: Bbox): GeoJSON.FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: [{ type: 'Feature', properties: {}, geometry: rectanglePolygon(bbox) }],
  }
}

/**
 * Canvas-relative pixel for a raw DOM mouse event, as `[x, y]` -- MapLibre's
 * `PointLike` (what `map.unproject` and `event.point` both traffic in) is either its
 * own `Point` class or exactly this tuple shape, never a plain `{x, y}` object.
 */
function pointFromClient(canvas: HTMLCanvasElement, clientX: number, clientY: number): [number, number] {
  const rect = canvas.getBoundingClientRect()
  return [clientX - rect.left, clientY - rect.top]
}

/**
 * Renders the confirm dialog (and otherwise nothing into React). Draws the rectangle
 * on the map while it is being dragged, and drives the count-check / confirm / page-load
 * pipeline once the drag ends.
 *
 * Mounted only while the tool is armed -- mount/unmount is what suspends and restores
 * `boxZoom`/`dragPan` for the whole session, exactly as `MeasurementLayer` suspends
 * `doubleClickZoom` for the whole time a measuring mode is on, not just per click.
 */
export function RectangleSelectTool({ layerId }: RectangleSelectToolProps) {
  const { mapRef, isLoaded } = useMap()
  const select = useSelection((state) => state.select)
  const [phase, setPhase] = useState<Phase>(IDLE)
  const dragRef = useRef<[number, number] | null>(null)
  /** Bumped on every new drag; guards a stale async continuation from a previous one. */
  const generationRef = useRef(0)

  async function load(bbox: Bbox, mode: SelectionMode, generation: number) {
    useRectangleSelect.getState().setLoading(true)
    try {
      const { fids, truncated } = await collectAllFids((cursor) =>
        fetchFeaturePage(
          { layerId, bbox, mode: useRectangleSelect.getState().touchMode, geometry: false, size: 1000 },
          cursor,
        ),
      )
      if (truncated) {
        // The count check and this load are two separate requests; the data can have
        // grown in between. Loading only part of it and calling that "the selection"
        // would be worse than refusing outright.
        toast.error(
          `Das Rechteck enthält mehr als ${formatCount(RECTANGLE_SELECT_MAX)} Objekte — mehr, als geladen werden kann. Bitte einen kleineren Ausschnitt wählen.`,
        )
        return
      }
      select(layerId, fids, mode)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : GENERIC_ERROR)
    } finally {
      useRectangleSelect.getState().setLoading(false)
      if (generationRef.current === generation) setPhase(IDLE)
    }
  }

  async function resolve(bbox: Bbox, mode: SelectionMode, generation: number) {
    useRectangleSelect.getState().setLoading(true)

    let totalCount: number
    try {
      // size=1&geometry=false: the cheapest possible request that still returns
      // totalCount, which the backend computes for every uncursored query anyway.
      const first = await fetchFeaturePage({
        layerId,
        bbox,
        mode: useRectangleSelect.getState().touchMode,
        geometry: false,
        size: 1,
      })
      totalCount = first.totalCount ?? 0
    } catch (error) {
      useRectangleSelect.getState().setLoading(false)
      if (generationRef.current === generation) setPhase(IDLE)
      toast.error(error instanceof ApiError ? error.message : GENERIC_ERROR)
      return
    }
    useRectangleSelect.getState().setLoading(false)
    // A newer drag started while this count request was in flight; its own `resolve`
    // owns the phase now, and this one has nothing left to contribute.
    if (generationRef.current !== generation) return

    if (exceedsMaximum(totalCount)) {
      setPhase(IDLE)
      toast.error(
        `Das Rechteck enthält ${formatCount(totalCount)} Objekte — mehr, als geladen werden kann (Obergrenze ${formatCount(RECTANGLE_SELECT_MAX)}). Bitte einen kleineren Ausschnitt wählen.`,
      )
      return
    }

    if (needsConfirmation(totalCount)) {
      setPhase({ type: 'confirm', bbox, mode, totalCount })
      return
    }

    await load(bbox, mode, generation)
  }

  // Always the latest `resolve`, read from inside the interaction effect below, which
  // is set up once per mount and would otherwise close over a stale `layerId`/`select`.
  const resolveRef = useRef(resolve)
  resolveRef.current = resolve

  // Source and layers: added once the style is ready, removed on unmount -- i.e. the
  // moment the tool is switched off, same lifecycle as `MeasurementLayer`'s sketch.
  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    if (!map.getSource(SOURCE_ID)) {
      map.addSource(SOURCE_ID, { type: 'geojson', data: EMPTY })
      map.addLayer({
        id: FILL_LAYER,
        type: 'fill',
        source: SOURCE_ID,
        paint: { 'fill-color': '#0f172a', 'fill-opacity': 0.08 },
      })
      map.addLayer({
        id: LINE_LAYER,
        type: 'line',
        source: SOURCE_ID,
        paint: { 'line-color': '#0f172a', 'line-width': 1.5, 'line-dasharray': [2, 2] },
      })
    }

    raiseOverlays(map)

    return () => {
      // Guarded: the map may already be gone (route change, StrictMode remount).
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
    const bbox = bboxOf(phase)
    source?.setData(bbox ? toFeatureCollection(bbox) : EMPTY)
  }, [mapRef, isLoaded, phase])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    // boxZoom is MapLibre's own Shift+Drag gesture, dragPan the plain one -- both would
    // otherwise fight this tool for the exact same drag. Suspended for the whole time
    // the tool is mounted, not only mid-drag: the contract calls for the former.
    const restoreBoxZoom = suspendHandler(map.boxZoom)
    const restoreDragPan = suspendHandler(map.dragPan)
    const canvas = map.getCanvas()
    const previousCursor = canvas.style.cursor
    canvas.style.cursor = 'crosshair'

    // Arrow functions, not `function` declarations: TS does not carry the narrowing
    // of `map` above into a hoisted function declaration (it could, in principle, be
    // invoked before the null check due to hoisting), but it does into a `const`
    // closure defined after that check.
    const toBbox = (a: [number, number], b: [number, number]): Bbox => {
      const from = map.unproject(a)
      const to = map.unproject(b)
      return bboxFromCorners([from.lng, from.lat], [to.lng, to.lat])
    }

    const down = (event: MapMouseEvent) => {
      // Only the primary button starts a rectangle; a right-click is left for the
      // browser/OS context menu, a middle-click for whatever it normally does.
      if (event.originalEvent.button !== 0) return
      event.preventDefault()
      generationRef.current += 1
      const start: [number, number] = [event.point.x, event.point.y]
      dragRef.current = start
      setPhase({ type: 'dragging', bbox: toBbox(start, start) })

      // Tracked on `window`, not on the map: a drag that leaves the map container
      // (common when the rectangle reaches an edge of the panel) must still finish
      // cleanly instead of leaving the tool stuck mid-drag.
      const windowMove = (moveEvent: MouseEvent) => {
        if (!dragRef.current) return
        const point = pointFromClient(canvas, moveEvent.clientX, moveEvent.clientY)
        setPhase({ type: 'dragging', bbox: toBbox(dragRef.current, point) })
      }

      const windowUp = (upEvent: MouseEvent) => {
        window.removeEventListener('mousemove', windowMove)
        window.removeEventListener('mouseup', windowUp)
        const dragStart = dragRef.current
        dragRef.current = null
        if (!dragStart) return

        const point = pointFromClient(canvas, upEvent.clientX, upEvent.clientY)
        if (!isMeaningfulDrag(dragStart, point, DRAG_THRESHOLD_PX)) {
          setPhase(IDLE)
          return
        }

        const bbox = toBbox(dragStart, point)
        const mode = modifierSelectionMode(upEvent)
        setPhase({ type: 'resolving', bbox, mode })
        void resolveRef.current(bbox, mode, generationRef.current)
      }

      window.addEventListener('mousemove', windowMove)
      window.addEventListener('mouseup', windowUp)
    }

    map.on('mousedown', down)

    return () => {
      map.off('mousedown', down)
      dragRef.current = null
      // Invalidates any in-flight `resolve`/`load` continuation from this session.
      generationRef.current += 1
      if (canvas.style.cursor === 'crosshair') canvas.style.cursor = previousCursor
      restoreBoxZoom()
      restoreDragPan()
    }
  }, [mapRef, isLoaded])

  if (phase.type !== 'confirm') return null

  return (
    <RectangleSelectConfirmDialog
      totalCount={phase.totalCount}
      onConfirm={() => void load(phase.bbox, phase.mode, generationRef.current)}
      onCancel={() => setPhase(IDLE)}
    />
  )
}
