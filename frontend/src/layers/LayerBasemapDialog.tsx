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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Slider } from '@/components/ui/slider'
import { useUpdateLayer, type LayerSummary } from '@/api/layers'
import { projectDetailQuery } from '@/api/projects'
import { BASEMAPS, resolveBasemap, resolveBasemapId } from '@/map/basemap'

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
  const updateLayer = useUpdateLayer(layer?.id ?? '', projectId)

  const [basemapChoice, setBasemapChoice] = useState<string>(FOLLOWS_PROJECT)
  const [opacityOverride, setOpacityOverride] = useState<number | null>(null)

  useEffect(() => {
    if (layer) {
      setBasemapChoice(layer.basemap != null ? resolveBasemapId(layer.basemap) : FOLLOWS_PROJECT)
      setOpacityOverride(layer.basemapOpacity ?? null)
    }
  }, [layer])

  const projectBasemap = resolveBasemap(project?.basemap)
  const projectOpacity = project?.basemapOpacity ?? 1
  const selectedBasemap = basemapChoice === FOLLOWS_PROJECT ? projectBasemap : resolveBasemap(basemapChoice)
  const displayedOpacity = opacityOverride ?? projectOpacity

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!layer) return

    try {
      await updateLayer.mutateAsync({
        basemap: basemapChoice === FOLLOWS_PROJECT ? null : basemapChoice,
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
                        : resolveBasemap(value).label
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={FOLLOWS_PROJECT}>
                    Karte des Projekts verwenden ({projectBasemap.label})
                  </SelectItem>
                  {BASEMAPS.map((basemap) => (
                    <SelectItem key={basemap.id} value={basemap.id}>
                      <span className="flex flex-col items-start">
                        <span>{basemap.label}</span>
                        <span className="text-xs text-muted-foreground">{basemap.hint}</span>
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

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
