import { createFileRoute } from '@tanstack/react-router'
import { ensureBasemapsLoaded } from '@/api/basemaps'
import { projectListInfiniteQuery } from '@/api/projects'
import { ProjectBrowser } from '@/projects/ProjectBrowser'

export const Route = createFileRoute('/')({
  // Prefetch the first page (unfiltered) so it is there on first paint instead of
  // flashing skeletons. `ensureQueryData` would fetch the plain array shape this
  // endpoint no longer returns -- the chain needs `ensureInfiniteQueryData` instead.
  // The basemap catalog runs alongside it: `MapPreview` needs it to resolve each
  // project's tile URLs, and both requests are independent of each other.
  loader: ({ context }) =>
    Promise.all([
      context.queryClient.ensureInfiniteQueryData(projectListInfiniteQuery('')),
      ensureBasemapsLoaded(context.queryClient),
    ]),
  component: ProjectBrowser,
})
