import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { layerKeys, useUpdateMapImageOpacity, type LayerSummary } from '@/api/layers'
import { createWriteQueue } from '@/styling/styleWriteQueue'

export interface MapImageOpacityEditor {
  /** The Deckkraft the panel renders, previewed value included. Defaults to fully opaque. */
  opacity: number
  /**
   * @param defer for the slider firing while the pointer is still down -- the map
   *   follows immediately either way; only the request waits (mirrors `useStyleEditor`).
   */
  apply: (next: number, options?: { defer?: boolean }) => void
  isSaving: boolean
}

/**
 * The write path for a Kartenbild's Deckkraft slider (`LayerProperties.tsx`) -- the
 * opacity counterpart to `styling/useStyleEditor.ts`, and built the same way: no local
 * draft, every change goes straight into the layer list cache `syncLayers` reads, and
 * the request follows separately (late, for a value still being dragged).
 *
 * Takes the bare `LayerSummary`, not `MapImageLayerSummary`: `LayerProperties` renders
 * both layer kinds and the Rules of Hooks forbid calling this only inside its Kartenbild
 * branch. Reading `layer.style?.opacity` is safe either way -- `LayerStyle` (a vector
 * layer) and `MapImageStyle` (a Kartenbild) both carry an `opacity` of the same type --
 * and `apply` is only ever reachable from the Deckkraft slider, which that same branch
 * is the only place rendering.
 */
export function useMapImageOpacityEditor(layer: LayerSummary, projectId: string): MapImageOpacityEditor {
  const queryClient = useQueryClient()
  const save = useUpdateMapImageOpacity(projectId)

  // Ref for the same reason `useStyleEditor` holds one: the queue is built once and
  // must always commit through the *current* mutation, not the one captured when it
  // was constructed.
  const saveRef = useRef(save)
  saveRef.current = save

  const [queue] = useState(() =>
    createWriteQueue<number>((write) => {
      saveRef.current.mutate(
        { layerId: write.layerId, opacity: write.style },
        { onError: () => toast.error('Das Programm konnte die Deckkraft nicht speichern') },
      )
    }),
  )

  // Same reasoning as `useStyleEditor`: a deferred change must survive the panel
  // switching to a different layer, and `layer.id` in the dependency list is what turns
  // this into a flush of exactly the layer being left.
  useEffect(() => queue.flush, [queue, layer.id])

  const apply = useCallback(
    (next: number, options: { defer?: boolean } = {}) => {
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) =>
        current?.map((entry) => (entry.id === layer.id ? { ...entry, style: { opacity: next } } : entry)),
      )
      queue.queue({ layerId: layer.id, style: next }, options)
    },
    [layer.id, projectId, queryClient, queue],
  )

  return { opacity: layer.style?.opacity ?? 1, apply, isSaving: save.isPending }
}
