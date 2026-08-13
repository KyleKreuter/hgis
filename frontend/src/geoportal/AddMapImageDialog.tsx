import { useState, type KeyboardEvent } from 'react'
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
import { MapImageSection } from './MapImageSection'

interface AddMapImageDialogProps {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Fired after the Kartenbild was added, so the caller can select it in the tree. */
  onCreated: (layerId: string) => void
}

/**
 * The second way a Kartenbild comes into a project (plan Stufe 4, "eigene WMS-Adresse"),
 * next to the Geoportal-backed one in `GeoportalDialog`. No catalog entry stands behind
 * this one -- the user names the service directly, and `datasetId` is left out of the
 * create call entirely (contract "das ist der Fall 'eigene WMS-Adresse'").
 *
 * Reuses `MapImageSection` for the picker itself once a service address is confirmed --
 * the picker does not care whether the address came from a catalog row or was typed.
 */
export function AddMapImageDialog({ projectId, open, onOpenChange, onCreated }: AddMapImageDialogProps) {
  const [urlInput, setUrlInput] = useState('')
  // Only set once the user asks to load it -- a capabilities request costs the target
  // service a round trip, so it must not fire on every keystroke.
  const [confirmedUrl, setConfirmedUrl] = useState<string | null>(null)

  function reset() {
    setUrlInput('')
    setConfirmedUrl(null)
  }

  function handleOpenChange(next: boolean) {
    if (!next) reset()
    onOpenChange(next)
  }

  function loadService() {
    const trimmed = urlInput.trim()
    if (trimmed === '') return
    setConfirmedUrl(trimmed)
  }

  function handleUrlKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault()
      loadService()
    }
  }

  function handleAdded(layerId: string) {
    onCreated(layerId)
    handleOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Eigener WMS-Dienst</DialogTitle>
          <DialogDescription>
            Fügt ein Kartenbild über eine eigene WMS-Adresse hinzu, ohne den Umweg über den
            Geoportal-Katalog.
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="wms-url">Dienstadresse</Label>
            <div className="flex gap-2">
              <Input
                id="wms-url"
                value={urlInput}
                onChange={(event) => setUrlInput(event.target.value)}
                onKeyDown={handleUrlKeyDown}
                placeholder="https://beispiel.de/wms"
                autoFocus
              />
              <Button type="button" variant="outline" onClick={loadService} disabled={urlInput.trim() === ''}>
                Layer laden
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              Mit oder ohne eigene Anfrageparameter. Das Programm unterstützt nur WMS
              1.3.0 in EPSG:3857.
            </p>
          </div>

          {confirmedUrl && (
            // Remounts on every confirmed address: a second "Layer laden" for a
            // different service must not keep the previous one's selection or name.
            <MapImageSection key={confirmedUrl} projectId={projectId} wmsUrl={confirmedUrl} onAdded={handleAdded} />
          )}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => handleOpenChange(false)}>
            Schließen
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
