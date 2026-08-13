import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { layerKeys, useUpdateLayerStyle, type LayerSummary } from '@/api/layers'
import { isPersistable } from './persistable'
import { createStyleWriteQueue } from './styleWriteQueue'
import type { LayerStyle } from './types'

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
 *
 * A late request is what made this tricky: the panel is not remounted when the active
 * layer changes, so a waiting write used to be sent against whichever layer was current
 * when its timer fired -- the style of one layer written onto another. Two things settle
 * that now. The write carries its own layer (`styleWriteQueue`, and `useUpdateLayerStyle`
 * takes it as a variable), and the layer effect below flushes before the panel moves on.
 */
export function useStyleEditor(layer: LayerSummary, projectId: string): StyleEditor {
  const queryClient = useQueryClient()
  const save = useUpdateLayerStyle(projectId)

  // The queue must survive re-renders, and the mutation it commits through must be the
  // current one -- hence the ref rather than closing over `save`, the same reasoning as
  // `useViewStateWriter`'s `saveRef`. Which layer a write lands on no longer depends on
  // this: that travels inside the write itself.
  const saveRef = useRef(save)
  saveRef.current = save

  // Built once per panel, never per render -- it holds the waiting write and its timer.
  const [queue] = useState(() =>
    createStyleWriteQueue((write) => {
      saveRef.current.mutate(write, {
        onError: () => toast.error('Das Programm konnte die Symbologie nicht speichern'),
      })
    }),
  )

  // Closing the panel while a deferred change is still waiting must not drop it -- and
  // neither must switching layers, which keeps this hook mounted and only changes
  // `layer`. Listing `layer.id` is what turns the unmount cleanup into a per-layer one:
  // the pending write goes out while it is still the write of the layer being left.
  useEffect(() => queue.flush, [queue, layer.id])

  const apply = useCallback(
    (next: LayerStyle | null, options: { defer?: boolean } = {}) => {
      queryClient.setQueryData<LayerSummary[]>(layerKeys.list(projectId), (current) =>
        current?.map((entry) => (entry.id === layer.id ? { ...entry, style: next } : entry)),
      )

      if (!isPersistable(next)) {
        // Previewed but not written, and the older pending change is dropped with it:
        // it described a style the user has already moved on from.
        queue.drop()
        return
      }

      queue.queue({ layerId: layer.id, style: next }, options)
    },
    [layer.id, projectId, queryClient, queue],
  )

  return { style: layer.style ?? null, apply, isSaving: save.isPending }
}
