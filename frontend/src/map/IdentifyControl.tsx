import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { LngLat, MapMouseEvent } from 'maplibre-gl'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { formatAttributeNumber } from '@/lib/format'
import { layerDetailQuery } from '@/api/layers'
import { featureDetailQuery } from '@/api/features'
import { useSelection } from '@/state/selection'
import { useMap } from './MapContext'
import { MANAGED_PREFIX, sourceIdFor } from './layerSpecs'

interface Hit {
  layerId: string
  fid: number
  lngLat: LngLat
}

interface IdentifyControlProps {
  /** Restricts hit-testing to the active layer; without one, every visible layer is queried. */
  activeLayerId: string | null
}

/**
 * Click on the map to identify a feature: selects it and shows its attributes.
 *
 * The attributes come from the feature API, never from the tile. Tiles carry only the
 * geometry and the fid -- everything else was left out on purpose so tiles stay small
 * (plan section 1), which makes a second request the price of a click and the reason
 * Identify is a request at all.
 */
export function IdentifyControl({ activeLayerId }: IdentifyControlProps) {
  const { mapRef, isLoaded } = useMap()
  const [hit, setHit] = useState<Hit | null>(null)
  const [screenPosition, setScreenPosition] = useState<{ x: number; y: number } | null>(null)
  const toggle = useSelection((state) => state.toggle)
  const clear = useSelection((state) => state.clear)

  useEffect(() => {
    const map = mapRef.current
    if (!map || !isLoaded) return

    function handleClick(event: MapMouseEvent) {
      const target = mapRef.current
      if (!target) return

      // Restricted to the active layer when there is one, so clicking through a stack
      // of layers stays predictable instead of returning whatever happens to be on top.
      const layerIds = target
        .getStyle()
        .layers.map((layer) => layer.id)
        .filter((id) =>
          activeLayerId
            ? id.startsWith(sourceIdFor(activeLayerId))
            : id.startsWith(MANAGED_PREFIX),
        )
        .filter((id) => target.getLayer(id))

      const features = target.queryRenderedFeatures(event.point, { layers: layerIds })
      const feature = features[0]

      if (!feature || feature.id === undefined) {
        setHit(null)
        clear()
        return
      }

      // The MapLibre layer id is "hgis-layer-<uuid>-render" and friends, so the catalog
      // id is what sits between the prefix and the role suffix. `-label` belongs in the
      // list: a styled layer's text sits on top and is what a click lands on first --
      // it reports the same feature of the same source, so it identifies just as well.
      const layerId = String(feature.layer.id)
        .slice(MANAGED_PREFIX.length)
        .replace(/-(render|polygon|line|point|label|selected)(-outline)?$/, '')

      const fid = Number(feature.id)
      setHit({ layerId, fid, lngLat: event.lngLat })
      toggle(layerId, fid)
    }

    map.on('click', handleClick)
    return () => {
      map.off('click', handleClick)
    }
  }, [mapRef, isLoaded, activeLayerId, toggle, clear])

  // The popup is anchored to a coordinate, not to a pixel, so it has to be re-projected
  // whenever the map moves -- otherwise it drifts away from its feature on every pan.
  useEffect(() => {
    const map = mapRef.current
    if (!map || !hit) {
      setScreenPosition(null)
      return
    }

    function reposition() {
      const target = mapRef.current
      if (!target || !hit) return
      const point = target.project(hit.lngLat)
      setScreenPosition({ x: point.x, y: point.y })
    }

    reposition()
    map.on('move', reposition)
    return () => {
      map.off('move', reposition)
    }
  }, [mapRef, hit])

  if (!hit || !screenPosition) return null

  return (
    <IdentifyPopup
      layerId={hit.layerId}
      fid={hit.fid}
      position={screenPosition}
      onClose={() => {
        setHit(null)
        clear()
      }}
    />
  )
}

/** Same rule as the attribute table: values are shown as data, without grouping. */
function formatValue(value: unknown): string {
  if (value === null || value === undefined) return 'NULL'
  return typeof value === 'number' ? formatAttributeNumber(value) : String(value)
}

function IdentifyPopup({
  layerId,
  fid,
  position,
  onClose,
}: {
  layerId: string
  fid: number
  position: { x: number; y: number }
  onClose: () => void
}) {
  const { data: layer } = useQuery(layerDetailQuery(layerId))
  const { data: feature, isPending, isError } = useQuery(featureDetailQuery(layerId, fid))

  return (
    <div
      className="absolute z-20 w-64 -translate-x-1/2 -translate-y-full rounded-md border bg-popover shadow-md"
      // Offset upwards so the popup sits above the click instead of under the cursor.
      style={{ left: position.x, top: position.y - 12 }}
    >
      <div className="flex items-center gap-1 border-b px-2 py-1">
        <span className="truncate text-xs font-medium">{layer?.name ?? 'Objekt'}</span>
        <span className="text-xs text-muted-foreground tabular-nums">#{fid}</span>
        <Button
          variant="ghost"
          size="icon-sm"
          className="ml-auto size-5"
          aria-label="Schließen"
          onClick={onClose}
        >
          <X className="size-3" />
        </Button>
      </div>

      <div className="max-h-56 overflow-auto p-2">
        {isPending && <p className="text-xs text-muted-foreground">Lädt…</p>}
        {isError && <p className="text-xs text-destructive">Attribute nicht abrufbar.</p>}
        {feature && layer && (
          <dl className="grid grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs">
            {layer.fields.map((field) => {
              const value = feature.properties[field.columnName]
              return (
                <div key={field.id} className="contents">
                  <dt className="truncate text-muted-foreground" title={field.sourceName}>
                    {field.sourceName}
                  </dt>
                  <dd className="truncate" title={formatValue(value)}>
                    {value === null || value === undefined ? (
                      <span className="text-muted-foreground/50 italic">NULL</span>
                    ) : (
                      formatValue(value)
                    )}
                  </dd>
                </div>
              )
            })}
          </dl>
        )}
      </div>
    </div>
  )
}
