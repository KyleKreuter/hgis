import { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { Image as ImageIcon, TriangleAlert } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError } from '@/api/client'
import { useCreateMapImageLayer } from '@/api/layers'
import { useWmsCapabilities, type WmsCapabilityLayer } from '@/api/wms'
import { formatWmsScaleLimits } from './wmsLayerHints'

interface MapImageSectionProps {
  projectId: string
  wmsUrl: string
  /**
   * The Geoportal dataset this service came from -- carried along so the backend can
   * attach the catalog's own provenance (contract "holt das Backend Herkunft und
   * Lizenz aus dem Katalog"), same as a FEATURES import. Left out entirely for "eigene
   * WMS-Adresse" (`AddMapImageDialog`), which is not backed by any catalog entry.
   */
  datasetId?: string
  onAdded: (layerId: string) => void
}

/**
 * The Kartenbild half of a Geoportal entry's detail pane (plan Stufe 4, "Aus dem
 * Geoportal-Dialog"). Fetches the service's own capabilities and lets the user choose
 * which of its layers to draw -- the catalog names only the service address, never a
 * layer, so this is unavoidable (wms-api-vertrag.md section 2).
 *
 * Owns its selection locally rather than lifting it into `GeoportalDialog`: nothing
 * outside this section depends on it, and the parent already resets this component by
 * remounting it (`key={wmsUrl}`) whenever the chosen dataset changes.
 */
export function MapImageSection({ projectId, wmsUrl, datasetId, onAdded }: MapImageSectionProps) {
  const capabilities = useWmsCapabilities(wmsUrl)
  const createLayer = useCreateMapImageLayer(projectId)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [name, setName] = useState('')

  function toggle(layerName: string) {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(layerName)) next.delete(layerName)
      else next.add(layerName)
      return next
    })
  }

  // Bottom-to-top in the service's own order, not the order the user clicked in --
  // `layers` on the create endpoint *is* the drawing order (wms-api-vertrag.md
  // section 1: "layers ist die Reihenfolge, in der der Dienst zeichnet"). The type
  // predicate is what makes a group (`name: null`) impossible to send by accident --
  // without it, `.map((layer) => layer.name)` would type as `(string | null)[]` and the
  // mistake would only surface as a `400` from the backend, not here.
  const orderedSelection = useMemo(
    () =>
      (capabilities.data?.layers ?? [])
        .filter((layer): layer is WmsCapabilityLayer & { name: string } => layer.name !== null && selected.has(layer.name))
        .map((layer) => layer.name),
    [capabilities.data, selected],
  )

  async function handleAdd() {
    if (!capabilities.data || orderedSelection.length === 0) return
    try {
      const created = await createLayer.mutateAsync({
        serviceUrl: capabilities.data.serviceUrl,
        layers: orderedSelection,
        imageFormat: capabilities.data.imageFormats[0] ?? 'image/png',
        name: name.trim() === '' ? undefined : name.trim(),
        datasetId,
      })
      toast.success(`Kartenbild „${created.name}" hinzugefügt`)
      onAdded(created.id)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Das Programm konnte das Kartenbild nicht anlegen')
    }
  }

  return (
    <div className="grid gap-2 border-t pt-3">
      <div className="grid gap-0.5">
        <span className="flex items-center gap-1.5 text-xs font-medium tracking-wide uppercase text-muted-foreground">
          <ImageIcon className="size-3.5" />
          Als Kartenbild hinzufügen
        </span>
        <p className="text-xs text-muted-foreground">
          Ein Kartenbild zeigt das fertige Bild des Dienstes, ohne einzelne Objekte. Wählen
          Sie, welche Layer des Dienstes gezeichnet werden.
        </p>
      </div>

      {capabilities.isPending && <p className="text-xs text-muted-foreground">Dienstbeschreibung wird geladen…</p>}
      {capabilities.isError && (
        <Alert variant="destructive">
          <AlertTitle>Dienst nicht verfügbar</AlertTitle>
          <AlertDescription>
            {capabilities.error instanceof ApiError
              ? capabilities.error.message
              : 'Das Programm konnte die Dienstbeschreibung nicht laden.'}
          </AlertDescription>
        </Alert>
      )}

      {capabilities.data && (
        <>
          <ul aria-label="Layer des Dienstes" className="max-h-48 overflow-y-auto rounded-md border p-1">
            {capabilities.data.layers.map((layer, index) => (
              <WmsLayerRow
                // A group has no name to key by -- position is stable within one
                // capabilities response, which is all a key needs to be.
                key={layer.name ?? `group-${index}`}
                layer={layer}
                checked={layer.name !== null && selected.has(layer.name)}
                onToggle={() => {
                  if (layer.name !== null) toggle(layer.name)
                }}
              />
            ))}
          </ul>

          {selected.size > 0 && (
            <div className="grid gap-1.5">
              <Label htmlFor="map-image-name">Name</Label>
              <Input
                id="map-image-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder={
                  capabilities.data.layers.find((layer) => layer.name !== null && selected.has(layer.name))
                    ?.title ?? capabilities.data.title
                }
              />
            </div>
          )}

          <Button
            type="button"
            variant="outline"
            onClick={handleAdd}
            disabled={selected.size === 0 || createLayer.isPending}
          >
            <ImageIcon className="size-3.5" />
            {createLayer.isPending ? 'Wird angelegt…' : 'Als Kartenbild hinzufügen'}
          </Button>
        </>
      )}
    </div>
  )
}

/** Indentation for one entry -- shared by a group heading and an ordinary layer row. */
function indentStyle(depth: number): { paddingLeft: string } {
  // 1.25rem per depth level -- enough to read as nesting without eating the row's
  // width at the deepest levels the Hamburg catalog actually uses (max 3-4).
  return { paddingLeft: `${0.5 + depth * 1.25}rem` }
}

function WmsLayerRow({
  layer,
  checked,
  onToggle,
}: {
  layer: WmsCapabilityLayer
  checked: boolean
  onToggle: () => void
}) {
  // A group (`name: null`) cannot be requested from the service -- it is a heading
  // that explains the layers nested under it, nothing more (contract addendum). No
  // checkbox, not a `<label>`/`<button>`, and it never reaches `onToggle`.
  if (layer.name === null) {
    return (
      <li
        style={indentStyle(layer.depth)}
        className="px-2 py-1.5 text-xs font-medium tracking-wide text-muted-foreground uppercase"
      >
        {layer.title}
      </li>
    )
  }

  const scaleLimits = formatWmsScaleLimits(layer.minScale, layer.maxScale)

  return (
    <li>
      <label
        style={indentStyle(layer.depth)}
        className="flex items-start gap-2 rounded px-2 py-1.5 text-left text-xs hover:bg-accent/50"
      >
        <Checkbox checked={checked} onCheckedChange={onToggle} className="mt-0.5" />
        <span className="min-w-0 flex-1">
          <span className="block truncate">{layer.title}</span>
          {(!layer.queryable || scaleLimits) && (
            <span className="flex flex-wrap gap-x-2 text-muted-foreground">
              {/* Told plainly, not hidden behind a click: the user should know before
                  choosing, not after (plan Stufe 4, "Angaben, die der Nutzer vor der
                  Wahl sehen sollte"). Objektinfo itself is a later stage (Stufe 5). */}
              {!layer.queryable && (
                <span className="flex items-center gap-1">
                  <TriangleAlert className="size-3" />
                  nicht abfragbar
                </span>
              )}
              {scaleLimits && <span>{scaleLimits}</span>}
            </span>
          )}
        </span>
      </label>
    </li>
  )
}
