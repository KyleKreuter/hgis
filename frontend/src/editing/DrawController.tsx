import { useEffect, useRef } from 'react'
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
import { boundsOf, findSnapTarget, type SnapCandidate, type SnapTarget } from './snapping'

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
  /** Read inside terra-draw's snap callback, which is created once and outlives a toggle. */
  const snapEnabledRef = useRef(snapEnabled)
  snapEnabledRef.current = snapEnabled

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
        [...snapCandidates.current.values()],
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
        // No snapping option on the point mode -- terra-draw offers it for lines and
        // polygons only. Placing a single point is the one case where it would matter
        // least, but it is a gap, not a decision.
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
    }

    // A finished shape is a new feature. terra-draw keeps drawing it until then, so this
    // is the first moment there is a geometry worth recording.
    draw.on('finish', (id, context) => {
      if (context.action !== 'draw') return
      const feature = draw.getSnapshot().find((entry) => entry.id === id)
      if (!feature || isHandle(feature)) return

      // The id terra-draw assigned came from the buffer's own counter, so it is already
      // the temporary fid -- passing it on keeps the two sides holding one id, not two.
      const fid = useEditing
        .getState()
        .addFeature(feature.geometry as GeoJSON.Geometry, {}, Number(id))
      lastGeometry.current.set(fid, JSON.stringify(feature.geometry))
      // Registering it as a snap target is the change handler's job -- terra-draw reports
      // the creation there, whichever way the feature came into being.
      // A freshly drawn feature is what the user is working on, so its attribute form
      // opens without them having to select it again.
      onSelectFeature(fid)
    })

    draw.on('select', (id) => onSelectFeature(Number(id)))

    draw.on('deselect', () => onSelectFeature(null))

    draw.on('change', (ids, type) => {
      // Snap candidates are maintained for every kind of change, not just edits: a
      // feature added from anywhere -- drawn, loaded, inserted -- has to be attachable
      // right away, and one that is gone must stop attracting the cursor.
      if (type === 'delete') {
        for (const id of ids) snapCandidates.current.delete(Number(id))
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

      for (const id of ids) {
        const fid = Number(id)
        const feature = snapshot.find((entry) => entry.id === id)
        if (!feature || isHandle(feature)) continue

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
      if (!target || !snapEnabledRef.current || snapCandidates.current.size === 0) {
        onSnapTarget(null)
        return
      }
      onSnapTarget(
        findSnapTarget(
          [event.lngLat.lng, event.lngLat.lat],
          [...snapCandidates.current.values()],
          ([lng, lat]) => target.project([lng, lat]),
        ),
      )
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
      onSnapTarget(null)
      onSnapUnavailable(null)
      // Deliberately not ending the editing session here: leaving the mode is
      // `useEditSession`'s decision, and this cleanup also runs on a plain reload.
    }
  }, [mapRef, isLoaded, layerId, queryClient, onSelectFeature, onSnapTarget, onSnapUnavailable, reloadNonce])

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
