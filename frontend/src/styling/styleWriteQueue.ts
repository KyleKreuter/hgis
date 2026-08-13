import type { LayerStyle } from './types'

/** How long a continuous control may keep changing before the change is written out. */
export const DEFER_MS = 400

/**
 * One write, together with the layer it belongs to.
 *
 * The layer id travels with the value rather than being read when the write finally
 * goes out: a deferred write outlives the moment it was made, and by then the panel may
 * well be showing a different layer.
 */
export interface Write<T> {
  layerId: string
  style: T
}

export interface WriteQueue<T> {
  /**
   * Takes the next write. `defer` is for controls that fire while the pointer is still
   * down (a colour picker or a Deckkraft-Regler has no reliable "done" event); anything
   * else commits at once. A write replaces whatever was still waiting -- that one
   * described a value the user has already moved on from.
   */
  queue: (write: Write<T>, options?: { defer?: boolean }) => void
  /** Throws away what is waiting without committing it. */
  drop: () => void
  /** Commits what is waiting, now. */
  flush: () => void
}

/**
 * The timing half of a debounced write path, free of React and of what is actually
 * being written -- the symbology panel's style and a Kartenbild's opacity slider
 * (`layers/useMapImageOpacityEditor.ts`) are both just `T` here.
 *
 * Originally written for symbology alone (hence `createStyleWriteQueue` below, kept as
 * the exact type this module used to export so `useStyleEditor` and its tests stay
 * untouched); generalised the moment a second continuous control needed the identical
 * timing rule. This is where the layer-mix-up used to live: a write held back for
 * {@link DEFER_MS} was once committed against whichever layer was active when the timer
 * fired. Here the target is fixed when the write is queued, and the caller only has to
 * make sure the queue is flushed before its panel changes layer.
 *
 * @param commit sends one write. Called synchronously from `queue` for an immediate
 *   write and from the timer or `flush` for a deferred one.
 */
export function createWriteQueue<T>(
  commit: (write: Write<T>) => void,
  deferMs: number = DEFER_MS,
): WriteQueue<T> {
  let pending: Write<T> | null = null
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

/** `Write<LayerStyle | null>`, kept as its own name for `useStyleEditor` and its tests. */
export type StyleWrite = Write<LayerStyle | null>

/** `WriteQueue<LayerStyle | null>`, same reason. */
export type StyleWriteQueue = WriteQueue<LayerStyle | null>

/** `createWriteQueue<LayerStyle | null>`, same reason -- see {@link createWriteQueue}. */
export function createStyleWriteQueue(
  commit: (write: StyleWrite) => void,
  deferMs: number = DEFER_MS,
): StyleWriteQueue {
  return createWriteQueue<LayerStyle | null>(commit, deferMs)
}
