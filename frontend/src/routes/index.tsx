import { createFileRoute } from '@tanstack/react-router'
import { projectListInfiniteQuery } from '@/api/projects'
import { ProjectBrowser } from '@/projects/ProjectBrowser'

export const Route = createFileRoute('/')({
  // Prefetch the first page (unfiltered) so it is there on first paint instead of
  // flashing skeletons. `ensureQueryData` would fetch the plain array shape this
  // endpoint no longer returns -- the chain needs `ensureInfiniteQueryData` instead.
  loader: ({ context }) =>
    context.queryClient.ensureInfiniteQueryData(projectListInfiniteQuery('')),
  component: ProjectBrowser,
})
