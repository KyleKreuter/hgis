import { useEffect, useState } from 'react'
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
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { triggerDownload } from '@/api/export'
import type { LayerSummary } from '@/api/layers'
import { useMap } from '../MapContext'
import type { AttributionPart } from '../basemap'
import { imageFilename } from './filename'
import {
  computeImageSize,
  describeImageSize,
  findPageChoice,
  DEFAULT_DPI,
  DEFAULT_PAGE_CHOICE_ID,
  PAGE_CHOICES,
  RESOLUTIONS,
} from './pageFormat'
import { readMaxRenderbufferSize, renderLimitMessage } from './renderLimit'
import { renderMapImage } from './renderMapImage'

interface MapImageDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Prefills the title -- the name the user already gave this map. */
  projectName: string
  /** Basemap notice plus the visible Geoportal layers', from `MapCanvas` via the context. */
  attribution: readonly AttributionPart[]
  /** Layers to potentially include in the legend. */
  layers?: readonly LayerSummary[]
}

/**
 * Picks title, page format and resolution, then writes the PNG (CONTRACT.md 13).
 *
 * The dialog does none of the rendering itself. It collects the three answers, works out
 * what they mean in pixels, and refuses up front where the graphics card cannot go that
 * large -- with the ceiling named, so the user can see which of the two pickers to turn
 * down rather than guess.
 */
export function MapImageDialog({
  open,
  onOpenChange,
  projectName,
  attribution,
  layers,
}: MapImageDialogProps) {
  const { mapRef } = useMap()
  const [title, setTitle] = useState(projectName)
  const [pageChoiceId, setPageChoiceId] = useState(DEFAULT_PAGE_CHOICE_ID)
  const [dpi, setDpi] = useState(DEFAULT_DPI)
  const [includeLegend, setIncludeLegend] = useState(true)
  const [isRendering, setIsRendering] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Read once per opening rather than on every render: the probe creates a WebGL context,
  // and a browser keeps only a handful of those alive at a time.
  const [maxRenderbufferSize, setMaxRenderbufferSize] = useState<number | null>(null)

  useEffect(() => {
    if (open) setMaxRenderbufferSize(readMaxRenderbufferSize())
  }, [open])

  // The map panel's own size, for "wie am Bildschirm". Zero while the map is not up yet,
  // which `computeImageSize` handles rather than dividing by it.
  const container = mapRef.current?.getContainer()
  const screen = {
    width: container?.clientWidth ?? 0,
    height: container?.clientHeight ?? 0,
  }

  const choice = findPageChoice(pageChoiceId)
  const size = computeImageSize(choice, dpi, screen)
  const limitMessage = renderLimitMessage(size, maxRenderbufferSize)

  function reset() {
    setTitle(projectName)
    setPageChoiceId(DEFAULT_PAGE_CHOICE_ID)
    setDpi(DEFAULT_DPI)
    setIncludeLegend(true)
    setError(null)
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    const map = mapRef.current
    if (!map || isRendering) return
    // Checked again on the way out, not only while rendering the message: the picker can
    // change between the two, and this is the last point before a buffer is allocated.
    if (limitMessage) {
      setError(limitMessage)
      return
    }

    setError(null)
    setIsRendering(true)
    try {
      const { blob, warnings } = await renderMapImage({
        source: map,
        title,
        size,
        attribution,
        maxRenderbufferSize,
        layers,
        includeLegend,
      })
      triggerDownload(blob, imageFilename(title))
      if (warnings.length > 0) {
        toast.warning('Das Bild wurde erzeugt. Ein Teil der Karte konnte nicht geladen werden.')
      }
      else {
        toast.success('Das Bild wurde erzeugt')
      }
      onOpenChange(false)
      reset()
    }
    catch (caught) {
      setError(
        caught instanceof Error
          ? caught.message
          : 'Das Programm konnte das Bild nicht erzeugen.',
      )
    }
    finally {
      setIsRendering(false)
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        // A running export keeps its own dialog open: closing it would leave the hidden
        // map rendering with nowhere to report to.
        if (isRendering) return
        if (!next) reset()
        onOpenChange(next)
      }}
    >
      <DialogContent className="sm:max-w-md">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Karte als Bild exportieren</DialogTitle>
            <DialogDescription>
              Das Bild zeigt den Ausschnitt, den Sie gerade sehen, mit Titel, Maßstab und
              Quellenangabe.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 py-4">
            <div className="grid gap-1.5">
              <Label htmlFor="map-image-title">Titel</Label>
              <Input
                id="map-image-title"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                placeholder="Ohne Titel"
                autoFocus
              />
            </div>

            <div className="grid gap-1.5">
              <Label htmlFor="map-image-format">Seitenformat</Label>
              <Select
                value={pageChoiceId}
                onValueChange={(value) => value && setPageChoiceId(value as string)}
              >
                <SelectTrigger id="map-image-format" className="w-full">
                  <SelectValue>
                    {(value: string) => findPageChoice(value).label}
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {PAGE_CHOICES.map((entry) => (
                    <SelectItem key={entry.id} value={entry.id}>
                      {entry.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="grid gap-1.5">
              <Label htmlFor="map-image-dpi">Auflösung</Label>
              <Select
                value={String(dpi)}
                onValueChange={(value) => value && setDpi(Number(value))}
              >
                <SelectTrigger id="map-image-dpi" className="w-full">
                  <SelectValue>
                    {(value: string) =>
                      RESOLUTIONS.find((entry) => String(entry.dpi) === value)?.label ?? value
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {RESOLUTIONS.map((entry) => (
                    <SelectItem key={entry.dpi} value={String(entry.dpi)}>
                      <span className="flex flex-col items-start">
                        <span>{entry.label}</span>
                        <span className="text-xs text-muted-foreground">{entry.hint}</span>
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="flex items-center space-x-2">
              <Checkbox
                id="map-image-legend"
                checked={includeLegend}
                onCheckedChange={(checked) => setIncludeLegend(checked === true)}
              />
              <Label htmlFor="map-image-legend" className="text-sm font-normal cursor-pointer">
                Legende anzeigen
              </Label>
            </div>

            <p className="text-xs text-muted-foreground">
              Das Bild wird {describeImageSize(size)} groß.
            </p>

            {/* The refusal stands where the size hint does, so cause and effect are in
                the same place -- and it appears while picking, not only after clicking. */}
            {(limitMessage || error) && (
              <p className="text-xs text-destructive">{limitMessage ?? error}</p>
            )}
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={isRendering}
            >
              Abbrechen
            </Button>
            <Button type="submit" disabled={isRendering || limitMessage !== null}>
              {isRendering ? 'Wird erzeugt…' : 'Bild erzeugen'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
