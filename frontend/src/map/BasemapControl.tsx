import { Layers } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useUpdateProject } from '@/api/projects'
import { BASEMAPS, basemapChange, resolveBasemap } from './basemap'

interface BasemapControlProps {
  projectId: string
  /** The raw `project.basemap`; an unknown value shows as the OSM fallback. */
  basemapId: string | null | undefined
}

/**
 * Picks the background map. A menu rather than a row of buttons: it stays one control
 * wide over the map at any number of entries, and Base UI's menu gives the whole thing
 * `menuitemradio` semantics, arrow-key navigation and focus return for free.
 *
 * The choice is written straight to the project (`PATCH /api/projects/{id}`). Nothing
 * is kept in local state -- `useUpdateProject` updates the cached project optimistically,
 * so the map, the attribution and the checkmark all follow the click within the same
 * render, and a failed request rolls all three back together.
 */
export function BasemapControl({ projectId, basemapId }: BasemapControlProps) {
  const { mutate: updateProject } = useUpdateProject(projectId)
  const current = resolveBasemap(basemapId)

  function select(chosen: string) {
    const basemap = basemapChange(basemapId, chosen)
    if (!basemap) return
    updateProject(
      { basemap },
      {
        onError: () => toast.error('Das Programm konnte die Hintergrundkarte nicht speichern'),
      },
    )
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            variant="outline"
            size="sm"
            className="max-w-52 shadow-sm"
            aria-label={`Hintergrundkarte: ${current.label}`}
          >
            <Layers />
            {/* Drops to the icon alone once the map panel gets narrow: the measurement
                readout sits in the opposite corner, and the two must not meet. The
                aria-label above carries the name either way. */}
            <span className="truncate @max-sm:hidden">{current.label}</span>
          </Button>
        }
      />
      {/* Aligned to the trigger's right edge: the control sits in the top right corner,
          so the popup has to grow inwards over the map, not off it. */}
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>Hintergrundkarte</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={current.id}
          onValueChange={(value: string) => select(value)}
        >
          {BASEMAPS.map((basemap) => (
            <DropdownMenuRadioItem key={basemap.id} value={basemap.id} className="items-start">
              <span className="flex flex-col">
                {basemap.label}
                <span className="text-xs text-muted-foreground">{basemap.hint}</span>
              </span>
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
