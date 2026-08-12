import type { LayerViewState, ViewStateSort } from '@/state/viewState'
import type { FilterMode } from './filterMode'

/**
 * 'search' is what a layer opens into: CONTRACT.md frames the syntax-free search as the
 * common case ("für den häufigsten Fall"). Mirrors `AttributeTable`'s own initial value.
 */
export const DEFAULT_FILTER_MODE: FilterMode = 'search'

/** The sort and filter the attribute table shows for one layer. */
export interface LayerTableState {
  sort: ViewStateSort | null
  mode: FilterMode
  text: string
  /**
   * Whether `mode`/`text` came from the saved working state rather than something just
   * typed -- what tells the restored-filter hint apart from an ordinary, freshly-entered
   * filter the user already knows is active (CONTRACT.md phase 17 rule 1).
   */
  restoredQuery: boolean
}

/**
 * What the table has to show for `layerId` after a switch, saved state or not.
 *
 * The point of the function is the "or not": `AttributeTable` stays mounted across a
 * layer switch, so a layer with nothing saved does not fall back to anything by itself --
 * it simply keeps whatever the previous layer left behind. That is how a search for "aaa"
 * in one layer ended up hiding all 1.000 objects of the next one, with a sort arrow the
 * server had never stored for it. Every field is therefore answered here, unconditionally.
 *
 * @param firstVisit whether this layer is being opened for the first time this session.
 *   Only then does a saved query count as *restored*: coming back to a layer whose filter
 *   the user typed themselves a moment ago does not need the hint a second time.
 */
export function layerTableStateOf(saved: LayerViewState, firstVisit: boolean): LayerTableState {
  return {
    sort: saved.sort,
    mode: saved.query?.mode ?? DEFAULT_FILTER_MODE,
    text: saved.query?.text ?? '',
    restoredQuery: firstVisit && saved.query !== null,
  }
}
