import type { LayerStyle } from './types'

/** How long a continuous control may keep changing before the change is written out. */
export const DEFER_MS = 400

/**
 * One symbology write, together with the layer it belongs to.
 *
 * The layer id travels with the style rather than being read when the write finally goes
 * out: a deferred write outlives the moment it was made, and by then the panel may well
 * be showing a different layer.
 */
export interface StyleWrite {
  layerId: string
  style: LayerStyle | null
}

export interface StyleWriteQueue {
  /**
   * Takes the next write. `defer` is for controls that fire while the pointer is still
   * down (a colour picker has no reliable "done" event); anything else commits at once.
   * A write replaces whatever was still waiting -- that one described a style the user
   * has already moved on from.
   */
  queue: (write: StyleWrite, options?: { defer?: boolean }) => void
  /** Throws away what is waiting without committing it. */
  drop: () => void
  /** Commits what is waiting, now. */
  flush: () => void
}

/**
 * The timing half of the symbology write path, free of React.
 *
 * Split out from `useStyleEditor` because this is where the layer-mix-up lived: a write
 * held back for {@link DEFER_MS} used to be committed against whichever layer was active
 * when the timer fired. Here the target is fixed when the write is queued, and the hook
 * only has to make sure the queue is flushed before the panel changes layer.
 *
 * @param commit sends one write. Called synchronously from `queue` for an immediate
 *   write and from the timer or `flush` for a deferred one.
 */
export function createStyleWriteQueue(
  commit: (write: StyleWrite) => void,
  deferMs: number = DEFER_MS,
): StyleWriteQueue {
  let pending: StyleWrite | null = null
  let timer: ReturnType<typeof setTimeout> | null = null

  function stopTimer() {
    if (timer === null) return
    clearTimeout(timer)
    timer = null
  }

  function flush() {
    stopTimer()
    const write = pending
    if (!write) return
    // Cleared before committing, not after: `commit` writes into the query cache, which
    // re-renders the panel and can queue the next write from inside this call. Clearing
    // afterwards would throw that newer write away.
    pending = null
    commit(write)
  }

  return {
    queue(write, options = {}) {
      stopTimer()
      pending = write
      if (options.defer) {
        timer = setTimeout(flush, deferMs)
      } else {
        flush()
      }
    },

    drop() {
      stopTimer()
      pending = null
    },

    flush,
  }
}
