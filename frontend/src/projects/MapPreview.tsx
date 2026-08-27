import { Map as MapIcon } from 'lucide-react'
import { useBasemaps } from '@/api/basemaps'
import type { ProjectSummary } from '@/api/projects'
import { previewTilesFor } from './previewTiles'

/**
 * The background map's tiles at the project's saved position, laid out as a 2x2 grid --
 * see CONTRACT.md phase 22. This shows *where* a project is, never *what* is in it: no
 * MapLibre instance, no data layers. The calculation lives in `previewTiles.ts`, kept
 * pure and tested on its own.
 */
export function MapPreview({
  project,
}: {
  project: Pick<ProjectSummary, 'center' | 'zoom' | 'extent' | 'basemap'>
}) {
  // Already prefetched by the route loader (`ensureBasemapsLoaded`) -- this never fires
  // a request of its own.
  const { data: catalog = [] } = useBasemaps()
  const tiles = previewTilesFor(project, catalog)

  if (tiles.length === 0) {
    return (
      <div className="flex aspect-video items-center justify-center bg-muted">
        <MapIcon className="size-6 text-muted-foreground" strokeWidth={1.25} />
      </div>
    )
  }

  return (
    <div className="grid aspect-video grid-cols-2 grid-rows-2 overflow-hidden bg-muted">
      {tiles.map((tile, index) => (
        // The preview is decoration, not content: `alt` stays empty so a screen reader
        // announces the project's name (read from the tile's card, not from here) and
        // not four tile coordinates. `loading="lazy"` keeps tiles that are off-screen
        // from ever being requested -- required by the tile server's usage policy once
        // the grid can grow without bound through reload.
        <img
          key={index}
          src={tile.url}
          loading="lazy"
          alt=""
          className="h-full w-full object-cover"
        />
      ))}
    </div>
  )
}
