import { useState } from 'react'
import { ImageDown } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useMap } from '../MapContext'
import { MapImageDialog } from './MapImageDialog'

interface MapImageControlProps {
  /** Prefills the image title. */
  projectName: string
}

/**
 * Opens the image export, next to the background map picker in the map's top right
 * corner.
 *
 * It sits on the map rather than in the workspace toolbar because that is what it acts
 * on: the extent, the rotation and the background map that are on screen right now. The
 * dialog also needs the live map instance, and `useMap()` only answers inside
 * `<MapCanvas>`.
 */
export function MapImageControl({ projectName }: MapImageControlProps) {
  const { attribution } = useMap()
  const [open, setOpen] = useState(false)

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        className="shadow-sm"
        title="Karte als Bild exportieren"
        aria-label="Karte als Bild exportieren"
        onClick={() => setOpen(true)}
      >
        <ImageDown />
      </Button>
      <MapImageDialog
        open={open}
        onOpenChange={setOpen}
        projectName={projectName}
        attribution={attribution}
      />
    </>
  )
}
