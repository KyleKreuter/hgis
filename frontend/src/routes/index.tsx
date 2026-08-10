import { createFileRoute } from '@tanstack/react-router'
import { projectListQuery } from '@/api/projects'
import { ProjectBrowser } from '@/projects/ProjectBrowser'

export const Route = createFileRoute('/')({
  // Prefetch so the list is there on first paint instead of flashing skeletons.
  loader: ({ context }) => context.queryClient.ensureQueryData(projectListQuery()),
  component: ProjectBrowser,
})
