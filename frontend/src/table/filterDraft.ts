/**
 * `FilterBar`'s draft/value reconciliation -- the debounced text a person is typing
 * against the committed `value` a parent hands down, and the guard that tells the two
 * apart when `value` changes for a reason other than this component's own debounce.
 *
 * Without the `lifted` half, any external change to `value` -- CONTRACT.md phase 17's
 * restored filter/search arriving after `FilterBar` has already mounted with an empty
 * one, chief among them, but also an ordinary mode switch or "Filter löschen" -- cannot
 * be told apart from `value` merely catching up with what this component itself just
 * sent up. Treating both the same way breaks one of two things: either an external
 * change is ignored (a restored filter never shows), or every keystroke made while a
 * debounce is still pending gets wiped the moment the parent re-renders with the
 * now-stale value that triggered it.
 */
export interface DraftSync {
  draft: string
  /** What this component itself last sent up via `onChange` -- not necessarily what the
   *  parent has confirmed yet, just the last value this side is responsible for. */
  lifted: string
}

/** The state a `FilterBar` mounts with -- nothing has diverged yet. */
export function initialDraftSync(value: string): DraftSync {
  return { draft: value, lifted: value }
}

/**
 * What a new `value` from the parent does to the draft. Adopted outright when it is not
 * what this component last lifted -- an external change always wins, even over an
 * in-progress edit, the same call CONTRACT.md phase 17 makes for a restored filter
 * landing after the bar has already mounted. Left alone otherwise, since that is `value`
 * catching up with a lift already reflected in `draft`, not something to react to.
 *
 * Returns the same `state` reference when nothing changes, so a caller wiring this into
 * `setState(current => reconcileDraftValue(current, value))` costs no re-render for the
 * common case where `value` and `lifted` already agree.
 */
export function reconcileDraftValue(state: DraftSync, value: string): DraftSync {
  if (value === state.lifted) return state
  return { draft: value, lifted: value }
}

/** Applied while typing -- `lifted` is left untouched, so the eventual lift of this
 *  `draft` is still recognised as self-inflicted once `value` catches up to it. */
export function editDraft(state: DraftSync, draft: string): DraftSync {
  return { ...state, draft }
}

/** Applied at the moment `draft` is sent up via `onChange`, immediate or debounced
 *  alike -- records it as this component's own doing before `value` has even changed.
 *  Takes no prior state: a lift replaces both fields outright, nothing carries over. */
export function liftDraft(draft: string): DraftSync {
  return { draft, lifted: draft }
}
