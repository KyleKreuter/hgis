import { useCallback, useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { layerKeys, useUpdateLayerStyle, type LayerSummary } from '@/api/layers'
import { isPersistable } from './persistable'
import type { LayerStyle } from './types'

/** How long a continuous control may keep changing before the change is written out. */
const DEFER_MS = 400

export interface StyleEditor {
  /** What the panel renders, the previewed value included. */
  style: LayerStyle | null
  /**
   * @param defer for controls that fire while the pointer is still down (a colour
   *   picker has no reliable "done" event). The map follows immediately either way;
   *   only the request waits.
   */
  apply: (next: LayerStyle | null, options?: { defer?: boolean }) => void
  isSaving: boolean
}

/**
 * The write path for the symbology panel.
 *
 * There is no local draft state: every change goes straight into the layer list cache,
 * which is what `MapLayerSync` diffs against -- so the map is the preview, and the panel
 * and the map cannot drift apart. The request follows separately, and for a colour that
 * is still being dragged it follows late.
 */
export function useStyleEditor(layer: LayerSummary, projectId: string): StyleEditor {
  const queryClient = useQueryClient()
  const save = useUpdateLayerStyle(layer.id, projectId)

  const pending = useRef<{ style: LayerStyle | null } | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  // The unmount effect must not re-run whenever the mutation object changes identity,
  // so it reads the current one through a ref instead of closing over it.
  const saveRef = useRef(save)
  saveRef.current = save

  const flush = useCallback(() => {
    if (timer.current !== null) {
      clearTimeout(timer.current)
      timer.current = null
    }
    if (pending.current) {
      saveRef.current.mutate(pending.current.style, {
        onError: () => toast.error('Symbologie konnte nicht gespeichert werden'),
      })
      pending.current = null
    }
  }, [])

  // Closing the panel while a deferred change is still waiting must not drop it.
  useEffect(() => flush, [flush])

  const apply = useCallback(
    (next: LayerStyle | null, options: { defer?: boolean } = {}) => {
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) =>
        current?.map((entry) => (entry.id === layer.id ? { ...entry, style: next } : entry)),
      )

      if (timer.current !== null) clearTimeout(timer.current)
      timer.current = null
      if (!isPersistable(next)) {
        // Previewed but not written, and the older pending change is dropped with it:
        // it described a style the user has already moved on from.
        pending.current = null
        return
      }

      pending.current = { style: next }
      if (options.defer) {
        timer.current = setTimeout(flush, DEFER_MS)
      }
      else {
        flush()
      }
    },
    [flush, layer.id, projectId, queryClient],
  )

  return { style: layer.style ?? null, apply, isSaving: save.isPending }
}
