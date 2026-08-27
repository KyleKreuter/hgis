import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { RotateCcw } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Slider } from '@/components/ui/slider'
import { useBasemaps } from '@/api/basemaps'
import { useUpdateLayer, type LayerSummary } from '@/api/layers'
import { projectDetailQuery } from '@/api/projects'
import { isCustomBasemapUrl, resolveBasemap, resolveBasemapId, validateBasemapUrlTemplate } from '@/map/basemap'
import { BasemapEntryDetails } from '@/map/BasemapEntryDetails'
import { CUSTOM_BASEMAP_OPTION, CustomBasemapUrlField } from '@/map/CustomBasemapUrlField'
import { groupBasemaps } from '@/map/groupBasemaps'

/** Not a real basemap id, so it can never collide with one from the catalog. */
const FOLLOWS_PROJECT = 'follows-project'

interface LayerBasemapDialogProps {
  layer: LayerSummary | null
  projectId: string
  onOpenChange: (open: boolean) => void
}

/**
 * Sets a layer's own background map and opacity from its action menu, without the
 * detour of selecting the layer and opening the map's own picker (CONTRACT.md phase
 * 18, "Aktionsmenü des Layers"). Both fields are decided independently, exactly like
 * the picker: choosing a map here leaves the opacity alone, and the other way round.
 */
export function LayerBasemapDialog({ layer, projectId, onOpenChange }: LayerBasemapDialogProps) {
  // Already in the cache from the workspace route's own load -- this never fires a
  // request of its own, it only reads the project's current basemap for the "folgt
  // dem Projekt" option and for the opacity a reset falls back to.
  const { data: project } = useQuery({ ...projectDetailQuery(projectId), enabled: layer !== null })
  // Likewise already prefetched by the workspace route's loader (`ensureBasemapsLoaded`).
  const { data: catalog = [] } = useBasemaps()
  const groups = groupBasemaps(catalog)
  const updateLayer = useUpdateLayer(layer?.id ?? '', projectId)

  const [basemapChoice, setBasemapChoice] = useState<string>(FOLLOWS_PROJECT)
  const [opacityOverride, setOpacityOverride] = useState<number | null>(null)
  const [customUrl, setCustomUrl] = useState('')
  const [customUrlSubmitted, setCustomUrlSubmitted] = useState(false)

  useEffect(() => {
    if (layer) {
      const stored = layer.basemap
      if (isCustomBasemapUrl(stored)) {
        setBasemapChoice(CUSTOM_BASEMAP_OPTION)
        setCustomUrl(stored!)
      }
      else {
        setBasemapChoice(stored != null ? resolveBasemapId(catalog, stored) : FOLLOWS_PROJECT)
        setCustomUrl('')
      }
      setOpacityOverride(layer.basemapOpacity ?? null)
      setCustomUrlSubmitted(false)
    }
    // `catalog` deliberately excluded: it never changes after the initial load
    // (VERTRAG.md), and re-running this on every catalog reference would fight the
    // user's own pick with the dialog's own "reset to the layer's stored value" logic.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layer])

  const projectBasemap = resolveBasemap(catalog, project?.basemap)
  const projectOpacity = project?.basemapOpacity ?? 1
  const isCustomChoice = basemapChoice === CUSTOM_BASEMAP_OPTION
  const selectedBasemap = isCustomChoice
    ? resolveBasemap(catalog, customUrl)
    : basemapChoice === FOLLOWS_PROJECT
      ? projectBasemap
      : resolveBasemap(catalog, basemapChoice)
  const displayedOpacity = opacityOverride ?? projectOpacity

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!layer) return

    if (isCustomChoice) {
      setCustomUrlSubmitted(true)
      if (validateBasemapUrlTemplate(customUrl) !== null) return
    }

    try {
      await updateLayer.mutateAsync({
        basemap:
          basemapChoice === FOLLOWS_PROJECT ? null : isCustomChoice ? customUrl : basemapChoice,
        basemapOpacity: opacityOverride,
      })
      onOpenChange(false)
    } catch {
      toast.error('Das Programm konnte die Hintergrundkarte des Layers nicht speichern')
    }
  }

  return (
    <Dialog open={Boolean(layer)} onOpenChange={(next) => !next && onOpenChange(false)}>
      <DialogContent className="sm:max-w-md">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Hintergrundkarte des Layers</DialogTitle>
            <DialogDescription>
              Diese Einstellung gilt nur für den Layer „{layer?.name}". Sie überschreibt die
              Karte des Projekts.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 py-4">
            <div className="grid gap-1.5">
              <Label htmlFor="layer-basemap">Karte</Label>
              <Select value={basemapChoice} onValueChange={(value) => value && setBasemapChoice(value)}>
                <SelectTrigger id="layer-basemap" className="w-full">
                  <SelectValue>
                    {(value: string) =>
                      value === FOLLOWS_PROJECT
                        ? `Karte des Projekts verwenden (${projectBasemap.label})`
                        : value === CUSTOM_BASEMAP_OPTION
                          ? 'Eigene Kachel-URL'
                          : resolveBasemap(catalog, value).label
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={FOLLOWS_PROJECT}>
                    Karte des Projekts verwenden ({projectBasemap.label})
                  </SelectItem>
                  {groups.map((group) => (
                    <SelectGroup key={group.group}>
                      <SelectLabel>{group.group}</SelectLabel>
                      {group.entries.map((entry) => (
                        <SelectItem key={entry.id} value={entry.id}>
                          <BasemapEntryDetails entry={entry} />
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  ))}
                  <SelectSeparator />
                  <SelectItem value={CUSTOM_BASEMAP_OPTION}>Eigene Kachel-URL…</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {isCustomChoice && (
              <CustomBasemapUrlField
                value={customUrl}
                onChange={setCustomUrl}
                id="layer-basemap-custom-url"
                showError={customUrlSubmitted}
              />
            )}

            {/* "Keine Hintergrundkarte" has no raster layer to carry an opacity, so the
                slider would sit there without effect -- hidden instead. */}
            {selectedBasemap.id !== 'none' && (
              <div className="grid gap-1.5">
                <Label>Deckkraft</Label>
                <div className="flex items-center gap-1.5">
                  <Slider
                    value={displayedOpacity}
                    min={0}
                    max={1}
                    step={0.05}
                    onValueChange={setOpacityOverride}
                    aria-label="Deckkraft der Hintergrundkarte"
                  />
                  <span className="w-9 shrink-0 text-right text-xs text-muted-foreground tabular-nums">
                    {Math.round(displayedOpacity * 100)} %
                  </span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    className="size-6 shrink-0"
                    disabled={opacityOverride === null}
                    title="Deckkraft des Projekts verwenden"
                    aria-label="Deckkraft des Projekts verwenden"
                    onClick={() => setOpacityOverride(null)}
                  >
                    <RotateCcw className="size-3.5" />
                  </Button>
                </div>
              </div>
            )}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Abbrechen
            </Button>
            <Button type="submit" disabled={updateLayer.isPending}>
              {updateLayer.isPending ? 'Wird gespeichert…' : 'Speichern'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
