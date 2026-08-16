import { useCallback, useEffect, useRef } from 'react'

export interface DeferredLayerJump {
  /** Jump now, or as soon as there is no unsaved work left. */
  request: (layerId: string) => void
  /** Forget a waiting jump -- for when the user picks a layer themselves. */
  cancel: () => void
}

/**
 * Holds a layer jump back while the user has unsaved work, and carries it out the moment
 * they no longer do.
 *
 * Moving the view away mid-geometry or with open cell edits would cost real work: the
 * drawing session and the table session both belong to the layer that is open, and a
 * switch ends them. So a jump that arrives at that moment waits. It is not dropped --
 * dropping it would leave this client on a layer the saved state no longer names, and
 * nothing would ever put that right.
 *
 * <p>Saving and discarding are the same thing here, and deliberately so: both end with an
 * empty buffer, and neither is a reason to stay. The caller only has to report *whether*
 * there is unsaved work, not what became of it.
 *
 * <p>Only the newest target survives a wait. Two changes during one drawing session mean
 * the second is where the state now stands; jumping to the first and then to the second
 * would move the view twice for one moment of attention.
 *
 * @param unsavedChanges how many unsaved changes the user has right now. A number rather
 *   than a boolean because that is what the workspace already computes, and because it
 *   changes on every edit -- which is what makes the effect below run at the moment the
 *   last one is saved or discarded.
 * @param jump carries out the move. Called at most once per requested target.
 */
export function useDeferredLayerJump(
  unsavedChanges: number,
  jump: (layerId: string) => void,
): DeferredLayerJump {
  const waiting = useRef<string | null>(null)
  // Both read from a callback that outlives the render it was made in -- the live
  // channel calls `request` whenever an event arrives, not while anything renders. Same
  // reasoning as `useViewState`'s `saveRef`: what matters is the value at call time.
  const jumpRef = useRef(jump)
  jumpRef.current = jump
  const unsavedRef = useRef(unsavedChanges)
  unsavedRef.current = unsavedChanges

  const request = useCallback((layerId: string) => {
    // Straight through when nothing is at stake. Going through the effect below instead
    // would never fire at all in the common case: `waiting` is a ref, so setting it
    // renders nothing, and an effect that nothing schedules does not run.
    if (unsavedRef.current === 0) {
      jumpRef.current(layerId)
      return
    }
    waiting.current = layerId
  }, [])

  const cancel = useCallback(() => {
    waiting.current = null
  }, [])

  // Fires on the render that brings the count to zero -- the moment the last change was
  // saved or discarded.
  useEffect(() => {
    if (unsavedChanges > 0) return
    const target = waiting.current
    if (target === null) return
    waiting.current = null
    jumpRef.current(target)
  }, [unsavedChanges])

  return { request, cancel }
}
