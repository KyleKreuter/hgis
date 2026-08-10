import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError } from '@/api/client'
import { useUpdateLayer, type LayerSummary } from '@/api/layers'

interface RenameLayerDialogProps {
  layer: LayerSummary | null
  projectId: string
  onOpenChange: (open: boolean) => void
}

/**
 * Renames the layer's display name only. The physical table keeps its generated name --
 * it is derived from the layer's UUID and never follows what the layer is called, which
 * is exactly what makes renaming free of consequence here.
 */
export function RenameLayerDialog({ layer, projectId, onOpenChange }: RenameLayerDialogProps) {
  const [name, setName] = useState('')
  const [error, setError] = useState<string>()
  const updateLayer = useUpdateLayer(layer?.id ?? '', projectId)

  useEffect(() => {
    if (layer) {
      setName(layer.name)
      setError(undefined)
    }
  }, [layer])

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!layer) return
    setError(undefined)

    try {
      await updateLayer.mutateAsync({ name })
      onOpenChange(false)
    } catch (caught) {
      if (caught instanceof ApiError) {
        setError(caught.fieldError('name') ?? caught.message)
      } else {
        toast.error('Layer konnte nicht umbenannt werden')
      }
    }
  }

  return (
    <Dialog open={Boolean(layer)} onOpenChange={(next) => !next && onOpenChange(false)}>
      <DialogContent className="sm:max-w-md">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Layer umbenennen</DialogTitle>
          </DialogHeader>

          <div className="grid gap-1.5 py-4">
            <Label htmlFor="layer-name">Name</Label>
            <Input
              id="layer-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoFocus
              aria-invalid={error ? true : undefined}
            />
            {error && <p className="text-xs text-destructive">{error}</p>}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Abbrechen
            </Button>
            <Button type="submit" disabled={!name.trim() || updateLayer.isPending}>
              {updateLayer.isPending ? 'Wird gespeichert…' : 'Speichern'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
