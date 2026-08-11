import { useCallback, useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  TerraDraw,
  TerraDrawLineStringMode,
  TerraDrawPointMode,
  TerraDrawPolygonMode,
  TerraDrawSelectMode,
} from 'terra-draw'
import { TerraDrawMapLibreGLAdapter } from 'terra-draw-maplibre-gl-adapter'
import type { GeometryType } from '@/api/layers'
import { api } from '@/api/client'
import type { FeaturePage } from '@/api/features'
import { useMap } from '@/map/MapContext'
import { useEditing, type DraftFeature } from '@/state/editing'
import { toSinglePart } from './singlePart'
import {
  boundsOf,
  findSnapTarget,
  isTargetInReach,
  type SnapCandidate,
  type SnapTarget,
} from './snapping'

/**
 * How many existing features are loaded into the editor at once.
 *
 * Above this the viewport holds more than anyone edits by hand, and every one of them
 * would become a draggable object with its own vertices. The limit is announced rather
 * than silently applied -- plan section D.1 makes the same call for snapping.
 */
const MAX_EDITABLE = 2000

export type DrawTool = 'select' | 'point' | 'linestring' | 'polygon'

/** Which drawing tools a layer can accept. A typed column cannot hold another kind. */
export function toolsFor(geometryType: GeometryType): DrawTool[] {
  switch (geometryType) {
    case 'MULTIPOINT':
      return ['select', 'point']
    case 'MULTILINESTRING':
      return ['select', 'linestring']
    case 'MULTIPOLYGON':
      return ['select', 'polygon']
    default:
      return ['select', 'point', 'linestring', 'polygon']
  }
}

interface DrawControllerProps {
  layerId: string
  geometryType: GeometryType
  tool: DrawTool
  /** Reports which feature the attribute form should show; null when nothing is selected. */
  onSelectFeature: (fid: number | null) => void
  /** Changing this rebuilds the editor from the server state -- see `useEditSession`. */
  reloadNonce: number
  snapEnabled: boolean
  /** Reports the coordinate the pointer would snap to, so `SnapMarker` can show it. */
  onSnapTarget: (target: SnapTarget | null) => void
  /** Why snapping cannot be trusted here, or null when it can. */
  onSnapUnavailable: (reason: string | null) => void
  /** Other layers to snap against. Their features are targets only, never editable. */
  snapSourceLayerIds: string[]
}

/**
 * Renders nothing. Owns the terra-draw instance and keeps it and the edit buffer in step.
 *
 * terra-draw draws the working copy itself, which is why there is no second GeoJSON
 * source here: everything it holds is unsaved, and `EditingTileFilter` hides the same
 * features in the tile layers so the old and the new version never overlap.
 */
export function DrawController({
  layerId,
  geometryType,
  tool,
  onSelectFeature,
  reloadNonce,
  snapEnabled,
  onSnapTarget,
  onSnapUnavailable,
  snapSourceLayerIds,
}: DrawControllerProps) {
  const { mapRef, isLoaded } = useMap()
  const queryClient = useQueryClient()
  const drawRef = useRef<TerraDraw | null>(null)
  /** What each editable feature looked like when it was loaded, for rowVersion and diffing. */
  const originals = useRef(new Map<number, DraftFeature>())
  /** Last geometry seen per feature, so a selection is not mistaken for an edit. */
  const lastGeometry = useRef(new Map<number, string>())
  /**
   * What snapping may attach to, keyed by fid so it can follow every change. Rebuilding
   * this only on load would leave freshly drawn shapes invisible to snapping -- and
   * drawing a second polygon flush against the first is precisely when it is wanted.
   */
  const snapCandidates = useRef(new Map<number, SnapCandidate>())
  /**
   * Snap targets from other layers, kept apart from the editable ones on purpose: a fid
   * only identifies a row within its own layer, so merging them into one fid-keyed map
   * would have features of one layer overwrite those of another.
   */
  const externalCandidates = useRef<SnapCandidate[]>([])
  /** Read inside terra-draw's snap callback, which is created once and outlives a toggle. */
  const snapEnabledRef = useRef(snapEnabled)
  snapEnabledRef.current = snapEnabled
  /**
   * The target the marker is currently showing.
   *
   * Kept because placing a point has to snap on its own (see `snapPlacedPoint`), and the
   * position the marker promised is the only honest answer at that moment.
   */
  const previewTarget = useRef<SnapTarget | null>(null)
  /**
   * Set while the undo/redo sync writes into terra-draw.
   *
   * terra-draw reports those writes as changes like any other. Feeding them back into the
   * buffer would recreate the very change that was just taken back, and undo would appear
   * to do nothing at all.
   */
  const applyingFromBuffer = useRef(false)
  const historyNonce = useEditing((state) => state.historyNonce)
  const lastHistoryNonce = useRef(historyNonce)

  /** Everything snapping may attach to: this layer's features plus the marked sources. */
  const allSnapCandidates = useCallback(
    () => [...snapCandidates.current.values(), ...externalCandidates.current],
    [],
  )

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    /**
     * terra-draw asks this on every pointer move while drawing or dragging a vertex.
     * Returning undefined means "no snap", which is what leaves the pointer free.
     */
    function snapTo(event: { lng: number; lat: number }, context: { project: (lng: number, lat: number) => { x: number; y: number } }) {
      if (!snapEnabledRef.current) {
        onSnapTarget(null)
        return undefined
      }
      const target = findSnapTarget(
        [event.lng, event.lat],
        allSnapCandidates(),
        ([lng, lat]) => context.project(lng, lat),
      )
      onSnapTarget(target)
      return target ? (target.position as [number, number]) : undefined
    }

    const draw = new TerraDraw({
      adapter: new TerraDrawMapLibreGLAdapter({ map }),
      // terra-draw hands out UUID4s by default and refuses anything else. Overriding the
      // strategy makes the fid itself the feature id: a positive one for a row that
      // exists, a negative one for a shape that does not yet -- the same convention the
      // edit buffer already uses, so no lookup table is needed to get from one to the
      // other.
      idStrategy: {
        isValidId: (id) => typeof id === 'number' && Number.isInteger(id),
        getId: () => useEditing.getState().takeTempId(),
      },
      modes: [
        new TerraDrawSelectMode({
          flags: Object.fromEntries(
            ['point', 'linestring', 'polygon'].map((mode) => [
              mode,
              {
                feature: {
                  draggable: true,
                  coordinates: {
                    draggable: true,
                    // Midpoints let a vertex be inserted by dragging the line between
                    // two existing ones, which is how a shape gets refined.
                    midpoints: true,
                    deletable: true,
                    snappable: { toCustom: snapTo },
                  },
                },
              },
            ]),
          ),
        }),
        // Takes no snapping option -- terra-draw offers one for lines and polygons only.
        // A placed point is corrected afterwards instead, in `snapPlacedPoint`.
        new TerraDrawPointMode(),
        new TerraDrawLineStringMode({ snapping: { toCustom: snapTo } }),
        new TerraDrawPolygonMode({ snapping: { toCustom: snapTo } }),
      ],
    })

    draw.start()
    drawRef.current = draw

    if (import.meta.env.DEV) {
      // Same reasoning as `__hgisMap`: the instance lives in a ref, so questions like
      // "did this feature actually get added" cannot be answered from outside without it.
      ;(window as unknown as Record<string, unknown>).__hgisDraw = draw
      // Snap candidates live in refs too, and "is this layer actually being snapped to"
      // has no other answer from outside.
      ;(window as unknown as Record<string, unknown>).__hgisSnap = () => ({
        eigene: snapCandidates.current.size,
        fremde: externalCandidates.current.length,
      })
      // The buffer and the drawing surface are two copies of the same thing, and when they
      // disagree neither one says so. Seeing both at once is what turned "undo does
      // nothing" into a precise cause.
      ;(window as unknown as Record<string, unknown>).__hgisBuffer = () => {
        const { buffer, undoStack, redoStack, historyNonce: nonce } = useEditing.getState()
        return {
          creates: Object.keys(buffer.creates).map(Number),
          updates: Object.keys(buffer.updates).map(Number),
          deletes: buffer.deletes,
          undoStack: undoStack.length,
          redoStack: redoStack.length,
          historyNonce: nonce,
          geladen: originals.current.size,
        }
      }
    }

    /**
     * Moves a just-placed point onto the snap target, and reports where it ended up.
     *
     * terra-draw has no snapping option on its point mode -- lines and polygons get one,
     * points do not -- so the point lands wherever the click was and is corrected here.
     *
     * The target is the one the marker was showing, never one recomputed from the click.
     * Recomputing would be wrong twice over: the point is already in terra-draw's store by
     * now, so the search would find the point itself at a distance of zero; and even
     * without that it could settle on a different target than the one under the marker,
     * which would make the marker a lie.
     */
    function snapPlacedPoint(id: string | number, geometry: GeoJSON.Geometry): GeoJSON.Geometry {
      const map = mapRef.current
      const target = previewTarget.current
      if (geometry.type !== 'Point' || !snapEnabledRef.current || !target || !map) return geometry

      const click = geometry.coordinates as [number, number]
      if (!isTargetInReach(target, click, ([lng, lat]) => map.project([lng, lat]))) {
        return geometry
      }

      const snapped: GeoJSON.Point = { type: 'Point', coordinates: target.position }
      // Recorded before the update so the change handler recognises its own correction:
      // it compares against this and would otherwise log a second, separate undo step for
      // a move the user never made.
      lastGeometry.current.set(Number(id), JSON.stringify(snapped))
      draw.updateFeatureGeometry(id, snapped)
      snapCandidates.current.set(Number(id), { geometry: snapped, bounds: boundsOf(snapped) })
      return snapped
    }

    // A finished shape is a new feature. terra-draw keeps drawing it until then, so this
    // is the first moment there is a geometry worth recording.
    draw.on('finish', (id, context) => {
      if (context.action !== 'draw') return
      const feature = draw.getSnapshot().find((entry) => entry.id === id)
      if (!feature || isHandle(feature)) return

      const geometry = snapPlacedPoint(id, feature.geometry as GeoJSON.Geometry)

      // The id terra-draw assigned came from the buffer's own counter, so it is already
      // the temporary fid -- passing it on keeps the two sides holding one id, not two.
      const fid = useEditing.getState().addFeature(geometry, {}, Number(id))
      lastGeometry.current.set(fid, JSON.stringify(geometry))
      // Registering it as a snap target is the change handler's job -- terra-draw reports
      // the creation there, whichever way the feature came into being.
      // A freshly drawn feature is what the user is working on, so its attribute form
      // opens without them having to select it again.
      onSelectFeature(fid)
    })

    draw.on('select', (id) => onSelectFeature(Number(id)))

    draw.on('deselect', () => onSelectFeature(null))

    draw.on('change', (ids, type) => {
      // The sync below writes into terra-draw itself and keeps every ref in step as it
      // goes. Acting on its echo here would undo the undo.
      if (applyingFromBuffer.current) return

      // Snap candidates are maintained for every kind of change, not just edits: a
      // feature added from anywhere -- drawn, loaded, inserted -- has to be attachable
      // right away, and one that is gone must stop attracting the cursor.
      if (type === 'delete') {
        const { buffer } = useEditing.getState()
        for (const id of ids) {
          const fid = Number(id)
          snapCandidates.current.delete(fid)

          // terra-draw's own handles are deleted whenever a selection ends, and they
          // report through this same event. By then the feature is gone from the store,
          // so `isHandle` has nothing left to inspect -- but a handle was never loaded
          // and never added to the buffer, and that tells them apart just as well.
          const original = originals.current.get(fid)
          if (!original && !buffer.creates[fid]) continue

          // Without this a deletion lived only in terra-draw: the feature vanished from
          // the map, the change counter stayed at zero, and saving wrote nothing. The
          // row came back on the next reload, having never been deleted at all.
          useEditing.getState().removeFeature(fid, original)
          lastGeometry.current.delete(fid)
          onSelectFeature(null)
        }
        return
      }
      if (type === 'create') {
        const created = draw.getSnapshot()
        for (const id of ids) {
          const feature = created.find((entry) => entry.id === id)
          if (!feature || isHandle(feature)) continue
          const geometry = feature.geometry as GeoJSON.Geometry
          snapCandidates.current.set(Number(id), { geometry, bounds: boundsOf(geometry) })
        }
        return
      }
      if (type !== 'update') return
      const snapshot = draw.getSnapshot()
      const { buffer: current } = useEditing.getState()

      for (const id of ids) {
        const fid = Number(id)
        const feature = snapshot.find((entry) => entry.id === id)
        if (!feature || isHandle(feature)) continue

        // A shape still being drawn grows with every pointer move, and terra-draw reports
        // each step as an update. Recording them turned a three-corner polygon into eight
        // undo entries -- so taking it back meant pressing undo eight times, exactly the
        // frustration plan section D.2 asks to avoid. It enters the buffer once, when
        // `finish` says it is a shape; until then it belongs to the tool alone.
        if (!originals.current.has(fid) && !current.creates[fid]) continue

        // terra-draw reports an update whenever a feature's properties change too --
        // selecting one is an update. Without this comparison a plain click would mark
        // the feature as edited, and saving would rewrite rows nobody touched.
        const serialised = JSON.stringify(feature.geometry)
        if (lastGeometry.current.get(fid) === serialised) continue
        lastGeometry.current.set(fid, serialised)

        const base = originals.current.get(fid) ?? {
          fid,
          geometry: feature.geometry as GeoJSON.Geometry,
          properties: {},
        }
        useEditing.getState().updateGeometry(base, feature.geometry as GeoJSON.Geometry)
        // Kept in step, so snapping never attaches to where a shape used to be.
        const moved = feature.geometry as GeoJSON.Geometry
        snapCandidates.current.set(fid, { geometry: moved, bounds: boundsOf(moved) })
      }
    })

    // terra-draw only asks for a snap position once a drawing is under way -- before the
    // first click of a polygon it never calls back. That is exactly when the preview is
    // needed, so the marker is driven from plain pointer movement instead. This computes
    // the display only; the snapping that actually places a vertex still goes through
    // terra-draw's callback above.
    function previewSnap(event: { lngLat: { lng: number; lat: number } }) {
      const target = mapRef.current
      const candidates = allSnapCandidates()
      if (!target || !snapEnabledRef.current || candidates.length === 0) {
        previewTarget.current = null
        onSnapTarget(null)
        return
      }
      const found = findSnapTarget(
        [event.lngLat.lng, event.lngLat.lat],
        candidates,
        ([lng, lat]) => target.project([lng, lat]),
      )
      // Held for the point tool, which snaps from here rather than through terra-draw.
      previewTarget.current = found
      onSnapTarget(found)
    }

    map.on('mousemove', previewSnap)

    // Existing features have to be inside terra-draw to be touchable at all. Loaded in
    // full precision from the feature API, never from the tiles: tile geometry is
    // quantised to the tile grid, and editing that would move every vertex slightly.
    void loadEditableFeatures()

    async function loadEditableFeatures() {
      const target = mapRef.current
      if (!target) return
      const bounds = target.getBounds()
      const bbox = [
        bounds.getWest(),
        bounds.getSouth(),
        bounds.getEast(),
        bounds.getNorth(),
      ].join(',')

      try {
        const page = await queryClient.fetchQuery({
          queryKey: ['layers', layerId, 'editable', bbox],
          queryFn: () =>
            api.get<FeaturePage>(
              `/api/layers/${layerId}/features?geometry=true&bbox=${bbox}&size=${MAX_EDITABLE}`,
            ),
          staleTime: 0,
        })

        if ((page.totalCount ?? 0) > MAX_EDITABLE) {
          // Snapping to a partial set is worse than not snapping: it would attach to
          // whichever features happened to load and quietly miss the ones that did not
          // (plan section D.1 asks for the reason to be stated, not for a silent retreat).
          const reason = `Zu viele Objekte im Ausschnitt (${page.totalCount}) — zum Einrasten bitte hineinzoomen.`
          onSnapUnavailable(reason)
          toast.warning(reason)
        } else {
          onSnapUnavailable(null)
        }

        // Layer columns are always multi-typed, terra-draw only knows single geometries.
        // Anything with more than one part cannot be represented as one editable feature
        // and is left out rather than reduced to its first part.
        const editable = page.features.flatMap((feature) => {
          if (!feature.geometry) return []
          const single = toSinglePart(feature.geometry as GeoJSON.Geometry)
          return single ? [{ feature, geometry: single }] : []
        })
        const multipart = page.features.length - editable.length

        if (multipart > 0) {
          toast.warning(
            `${multipart} mehrteilige Objekte sind nicht bearbeitbar und bleiben unverändert.`,
          )
        }
        if (editable.length === 0) return

        const added = draw.addFeatures(
          editable.map(({ feature, geometry }) => ({
            id: feature.fid,
            type: 'Feature' as const,
            geometry,
            properties: { mode: modeFor(geometry) },
          })),
        )

        // Full precision, straight from the feature API -- never the tile geometry, which
        // is quantised to the tile grid (plan section D.1).
        for (const { feature, geometry } of editable) {
          snapCandidates.current.set(feature.fid, { geometry, bounds: boundsOf(geometry) })
        }

        for (const { feature, geometry } of editable) {
          lastGeometry.current.set(feature.fid, JSON.stringify(geometry))
          originals.current.set(feature.fid, {
            fid: feature.fid,
            geometry,
            properties: feature.properties,
            rowVersion: feature.rowVersion,
          })
        }

        const rejected = added.filter((entry) => !entry.valid).length
        if (rejected > 0) {
          toast.warning(`${rejected} Objekte konnten nicht zum Bearbeiten geladen werden.`)
        }
      } catch {
        toast.error('Objekte konnten nicht zum Bearbeiten geladen werden')
      }
    }

    return () => {
      map.off('mousemove', previewSnap)
      // stop() removes terra-draw's own layers from the map. Without it a second
      // activation would add them again on top of the first set.
      draw.stop()
      drawRef.current = null
      originals.current.clear()
      lastGeometry.current.clear()
      snapCandidates.current.clear()
      externalCandidates.current = []
      previewTarget.current = null
      onSnapTarget(null)
      onSnapUnavailable(null)
      // Deliberately not ending the editing session here: leaving the mode is
      // `useEditSession`'s decision, and this cleanup also runs on a plain reload.
    }
  }, [mapRef, isLoaded, layerId, queryClient, onSelectFeature, onSnapTarget, onSnapUnavailable, allSnapCandidates, reloadNonce])

  /**
   * Loads the marked snap sources.
   *
   * Deliberately its own effect: toggling a source must not rebuild terra-draw, which
   * holds every unsaved change. These features are targets only -- they are never handed
   * to the drawing tool, so nothing about them can be moved by accident.
   */
  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded || snapSourceLayerIds.length === 0) {
      externalCandidates.current = []
      return
    }

    // The layer being edited is always a snap target through its editable features. If it
    // is also marked as a source -- easily done by switching the active layer to one --
    // loading it again would put every one of its features into the candidate set twice.
    const sources = snapSourceLayerIds.filter((id) => id !== layerId)
    if (sources.length === 0) {
      externalCandidates.current = []
      return
    }

    let cancelled = false
    const bounds = map.getBounds()
    const bbox = [bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()].join(',')

    async function loadSources() {
      const loaded: SnapCandidate[] = []

      for (const sourceLayerId of sources) {
        try {
          const page = await queryClient.fetchQuery({
            queryKey: ['layers', sourceLayerId, 'snap-source', bbox],
            queryFn: () =>
              api.get<FeaturePage>(
                `/api/layers/${sourceLayerId}/features?geometry=true&bbox=${bbox}&size=${MAX_EDITABLE}`,
              ),
            staleTime: 30_000,
          })

          for (const feature of page.features) {
            if (!feature.geometry) continue
            // Multi-part geometry is fine here, unlike for editing: snapping walks
            // coordinates and never has to represent the feature as one editable shape.
            const geometry = feature.geometry as GeoJSON.Geometry
            loaded.push({ geometry, bounds: boundsOf(geometry) })
          }
        } catch {
          toast.error('Ein Layer konnte nicht als Fangquelle geladen werden')
        }
      }

      if (!cancelled) externalCandidates.current = loaded
    }

    void loadSources()
    return () => {
      cancelled = true
    }
  }, [mapRef, isLoaded, layerId, queryClient, snapSourceLayerIds])

  /**
   * Brings the drawing surface back in line after undo or redo.
   *
   * Undo applies patches to the buffer, and nothing else notices: terra-draw keeps its own
   * copy of every geometry. Without this the counter dropped to "keine Änderungen" while
   * the taken-back shape stayed on screen -- and saving then wrote something different
   * from what was visible.
   *
   * Rebuilt from the buffer rather than played back step by step: a patch says how the
   * buffer changed, not what the map should now show, and the two would drift apart over
   * a long history.
   */
  useEffect(() => {
    if (lastHistoryNonce.current === historyNonce) return
    lastHistoryNonce.current = historyNonce

    const draw = drawRef.current
    if (!draw) return
    const { buffer } = useEditing.getState()

    // What the surface should hold: everything loaded, minus what is deleted, with edited
    // geometry taking precedence -- plus everything drawn since.
    const wanted = new Map<number, GeoJSON.Geometry>()
    for (const [fid, original] of originals.current) {
      if (buffer.deletes.includes(fid)) continue
      wanted.set(fid, buffer.updates[fid]?.geometry ?? original.geometry)
    }
    for (const draft of Object.values(buffer.creates)) {
      wanted.set(draft.fid, draft.geometry)
    }

    const present = new Map(
      draw
        .getSnapshot()
        .filter((feature) => !isHandle(feature))
        .map((feature) => [Number(feature.id), feature]),
    )

    const obsolete = [...present.keys()].filter((fid) => !wanted.has(fid))

    applyingFromBuffer.current = true
    try {
      if (obsolete.length > 0) {
        draw.removeFeatures(obsolete)
        for (const fid of obsolete) {
          snapCandidates.current.delete(fid)
          lastGeometry.current.delete(fid)
        }
      }

      const missing: Parameters<typeof draw.addFeatures>[0] = []
      for (const [fid, geometry] of wanted) {
        const serialised = JSON.stringify(geometry)
        const existing = present.get(fid)
        if (!existing) {
          missing.push({
            id: fid,
            type: 'Feature',
            geometry: geometry as GeoJSON.Point | GeoJSON.LineString | GeoJSON.Polygon,
            properties: { mode: modeFor(geometry) },
          })
        } else if (JSON.stringify(existing.geometry) !== serialised) {
          draw.updateFeatureGeometry(fid, geometry as GeoJSON.Point | GeoJSON.LineString | GeoJSON.Polygon)
        } else {
          continue
        }
        lastGeometry.current.set(fid, serialised)
        snapCandidates.current.set(fid, { geometry, bounds: boundsOf(geometry) })
      }
      if (missing.length > 0) {
        // Checked rather than fired and forgotten. terra-draw refuses a feature whose
        // coordinates carry more than nine decimal places, and it did: a shape drawn onto
        // a snapped edge could not be restored, so undo emptied the surface for good while
        // the counter claimed the change was back. Rounding computed snap positions fixed
        // the cause; this keeps the next such refusal from being silent.
        const rejected = draw.addFeatures(missing).filter((entry) => !entry.valid)
        if (rejected.length > 0) {
          toast.warning(`${rejected.length} Objekte konnten nicht wiederhergestellt werden.`)
        }
      }
    } finally {
      applyingFromBuffer.current = false
    }
  }, [historyNonce])

  // Switching tools must not tear the instance down: terra-draw carries the working copy,
  // and recreating it would drop everything drawn so far.
  useEffect(() => {
    drawRef.current?.setMode(tool)
  }, [tool])

  void geometryType
  return null
}

/**
 * True for terra-draw's own drag handles.
 *
 * Selecting a feature makes terra-draw add a point per vertex and per segment midpoint,
 * as features in the very same store. They pass through the id strategy like anything
 * else and therefore carry temporary fids -- so without this filter, selecting one
 * building would put half a dozen phantom "new features" into the edit buffer, and
 * saving would try to write them.
 */
function isHandle(feature: { properties?: Record<string, unknown> | null }): boolean {
  const properties = feature.properties ?? {}
  return (
    properties.mode === 'select' ||
    properties.selectionPoint === true ||
    properties.midPoint === true
  )
}

/** terra-draw tracks which mode a feature belongs to; it has to match the geometry. */
function modeFor(geometry: GeoJSON.Geometry): DrawTool {
  switch (geometry.type) {
    case 'Point':
    case 'MultiPoint':
      return 'point'
    case 'LineString':
    case 'MultiLineString':
      return 'linestring'
    default:
      return 'polygon'
  }
}
