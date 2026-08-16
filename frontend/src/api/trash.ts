import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { layerKeys } from './layers'
import { projectKeys } from './projects'

/**
 * One layer sitting in a project's Papierkorb (contract "Schreibstufe" Paket 1 `schutz`,
 * table "GET /api/projects/{projectId}/trash"). `DELETE /api/layers/{layerId}` no longer
 * drops the table -- it only marks the catalog entry, and these are exactly the four
 * things the contract says that listing has to carry: the layer's name, when it was
 * moved here, who moved it, and how much would be lost by purging it.
 *
 * Built against the written contract, not against a running backend -- `schutz` builds
 * these same endpoints in a parallel worktree. `deletedBy` is nullable because the
 * contract never says the server can always attribute a deletion to someone.
 */
export interface TrashEntry {
  id: string
  name: string
  deletedAt: string
  deletedBy: string | null
  featureCount: number
}

export const trashKeys = {
  list: (projectId: string) => ['projects', projectId, 'trash'] as const,
}

export const trashListQuery = (projectId: string) =>
  queryOptions({
    queryKey: trashKeys.list(projectId),
    queryFn: () => api.get<TrashEntry[]>(`/api/projects/${projectId}/trash`),
  })

/**
 * Moves a layer back out of the Papierkorb (contract "zurueckholen"). Unlike
 * `useUpdateLayer`, this does not patch either cache optimistically: only the server
 * knows the restored layer's position among the others, so both the trash listing and
 * the ordinary layer list are simply marked stale and refetched.
 */
export function useRestoreLayer(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (layerId: string) => api.post<unknown>(`/api/layers/${layerId}/restore`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: trashKeys.list(projectId) })
      queryClient.invalidateQueries({ queryKey: layerKeys.list(projectId) })
      // The project tile shows layer/feature totals (ProjectBrowser), which a restored
      // layer changes same as a newly created one (see useCreateLayer in api/layers.ts).
      queryClient.invalidateQueries({ queryKey: projectKeys.all })
    },
  })
}

/**
 * Drops a trashed layer for good (contract "DELETE /api/layers/{layerId}/purge ...
 * jetzt das DROP TABLE"). `useDeleteLayer` (api/layers.ts) only moves a layer into the
 * Papierkorb now -- this is the one call left in the whole delete story that is
 * genuinely irreversible, which is why `TrashDialog` gates it behind its own
 * confirmation (`PurgeLayerDialog`) instead of firing it directly from a row.
 */
export function usePurgeLayer(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (layerId: string) => api.delete<void>(`/api/layers/${layerId}/purge`),
    onSuccess: (_result, layerId) => {
      queryClient.setQueryData<TrashEntry[]>(trashKeys.list(projectId), (current) =>
        current?.filter((entry) => entry.id !== layerId),
      )
      queryClient.invalidateQueries({ queryKey: trashKeys.list(projectId) })
    },
  })
}
