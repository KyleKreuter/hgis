import type { FilterMode } from '@/table/filterMode'

/**
 * The project-level working state (CONTRACT.md phase 17, schema B): which layer is
 * open, and per layer, its sort, its filter/search and its selection. Everything here
 * is plain data and plain functions, deliberately free of React, TanStack Query and the
 * zustand stores that hold the *live* versions of the same three things (`AttributeTable`'s
 * own state, `state/selection.ts`) -- `state/useViewState.ts` is what wires this to those.
 */

/** One layer's active sort -- keyed by `field` (columnName), mirrors `AttributeTable`. */
export interface ViewStateSort {
  field: string
  desc: boolean
}

/** The filter bar's committed text together with which of its two modes it belongs to. */
export interface ViewStateQuery {
  mode: FilterMode
  text: string
}

/** What is remembered for one layer. */
export interface LayerViewState {
  sort: ViewStateSort | null
  query: ViewStateQuery | null
  selection: number[]
}

/** The whole document, as `GET`/`PUT /api/projects/{id}/view-state` exchange it. */
export interface ViewStateDocument {
  version: 1
  activeLayerId: string | null
  layers: Record<string, LayerViewState>
}

/** What a project without a saved state answers with -- the server's own empty default,
 *  and what a still-loading query is treated as until the real answer arrives. */
export const EMPTY_VIEW_STATE: ViewStateDocument = { version: 1, activeLayerId: null, layers: {} }

const EMPTY_LAYER_STATE: LayerViewState = { sort: null, query: null, selection: [] }

/** `text` beyond this is rejected by the server (CONTRACT.md); clipped here so a long
 *  paste cannot turn an ordinary filter change into a failed save. */
const MAX_QUERY_TEXT_LENGTH = 2000

/** Above this many fids the server rejects the whole write (CONTRACT.md's "Grenze"). */
export const SELECTION_SAVE_LIMIT = 10_000

/** A layer's remembered state, or the all-empty default for one that was never saved. */
export function layerStateOf(document: ViewStateDocument, layerId: string): LayerViewState {
  return document.layers[layerId] ?? EMPTY_LAYER_STATE
}

function withLayerState(
  document: ViewStateDocument,
  layerId: string,
  patch: Partial<LayerViewState>,
): ViewStateDocument {
  return {
    ...document,
    layers: { ...document.layers, [layerId]: { ...layerStateOf(document, layerId), ...patch } },
  }
}

/** Records which layer is open. `null` clears it, the same as no layer selected yet. */
export function withActiveLayer(document: ViewStateDocument, layerId: string | null): ViewStateDocument {
  return { ...document, activeLayerId: layerId }
}

/** A new document with `layerId`'s sort replaced; its query and selection carry over. */
export function withSort(
  document: ViewStateDocument,
  layerId: string,
  sort: ViewStateSort | null,
): ViewStateDocument {
  return withLayerState(document, layerId, { sort })
}

/** A new document with `layerId`'s query replaced; its sort and selection carry over. */
export function withQuery(
  document: ViewStateDocument,
  layerId: string,
  query: ViewStateQuery | null,
): ViewStateDocument {
  const bounded =
    query && query.text.length > MAX_QUERY_TEXT_LENGTH
      ? { ...query, text: query.text.slice(0, MAX_QUERY_TEXT_LENGTH) }
      : query
  return withLayerState(document, layerId, { query: bounded })
}

/**
 * The filter bar's mode and committed text as a `ViewStateQuery`, or `null` for an
 * empty one -- an empty string is "no restriction" and is written as `null` (CONTRACT.md
 * schema: "oder null"), never as an object holding an empty string.
 */
export function queryOf(mode: FilterMode, text: string): ViewStateQuery | null {
  return text ? { mode, text } : null
}

function withSelection(document: ViewStateDocument, layerId: string, selection: number[]): ViewStateDocument {
  return withLayerState(document, layerId, { selection })
}

/** Whether this many selected fids still fit under the server's per-layer ceiling. */
export function selectionWithinSaveLimit(count: number): boolean {
  return count <= SELECTION_SAVE_LIMIT
}

/** What writing a selection results in -- see CONTRACT.md's "Grenze". */
export interface SelectionWritePlan {
  /** The document to send, or `null` when the selection is over the limit -- nothing is
   *  sent then, and the caller decides how to tell the user (`state/useViewState.ts`). */
  document: ViewStateDocument | null
  overLimit: boolean
}

/**
 * Builds the document a selection change results in, without deciding whether to send
 * it -- above `SELECTION_SAVE_LIMIT` nothing is built at all ("Senden Sie gar nicht erst",
 * CONTRACT.md), so a caller that checks `overLimit` never accidentally writes a selection
 * the server would reject anyway.
 */
export function planSelectionWrite(
  document: ViewStateDocument,
  layerId: string,
  selection: readonly number[],
): SelectionWritePlan {
  if (!selectionWithinSaveLimit(selection.length)) {
    return { document: null, overLimit: true }
  }
  return { document: withSelection(document, layerId, [...selection]), overLimit: false }
}

/**
 * The layer to switch to because the URL's `layer` search param says nothing -- see
 * CONTRACT.md rule 4: an explicit address always wins, the saved state only fills in
 * when the address is silent. Returns `null` when there is nothing to restore either
 * way (the address already names a layer, or none was ever saved).
 *
 * <p>This is about *opening* a project. Once it is open, the address no longer blocks:
 * a change to the saved active layer moves the view, see {@link activeLayerJumpTarget}.
 */
export function shouldRestoreActiveLayer(
  urlLayerId: string | undefined,
  document: ViewStateDocument,
): string | null {
  if (urlLayerId !== undefined) return null
  return document.activeLayerId
}

/** What {@link activeLayerJumpTarget} needs to know. */
export interface ActiveLayerJump {
  /**
   * The saved active layer as this client last knew it, or `undefined` before it has
   * read one. Comparing against it is what turns a reported *state* into the *change*
   * this client has not seen yet -- the channel never reports changes, and it must not:
   * deriving one here is possible precisely because the current state can always be
   * read again.
   */
  known: string | null | undefined
  /** The saved active layer as it now stands. */
  stored: string | null
  /** The layer this client currently has open, from the address. */
  open: string | null
}

/**
 * The layer the view has to move to, or `null` for "stay".
 *
 * Three reasons not to move, and each one matters:
 *
 * <ul>
 * <li><b>Nothing to compare against.</b> Before this client has read the saved state
 *     once, every value looks new, and the first event would move a view that was never
 *     asked to move.
 * <li><b>The saved layer did not change.</b> An event about a *selection* carries the
 *     unchanged active layer with it. Moving on that would drag a user off the layer
 *     their address explicitly named, without anyone having switched anything.
 * <li><b>Already there.</b> Someone switched to the layer this client has open anyway.
 * </ul>
 *
 * <p>A fourth case needs no line of its own: a saved active layer of `null` means "none
 * open", which is not a destination -- and it falls out as `null` here on its own, since
 * that is what would be returned. A guard for it would read well and change nothing,
 * which is worse than no guard: nothing could ever tell whether it still worked.
 */
export function activeLayerJumpTarget({ known, stored, open }: ActiveLayerJump): string | null {
  if (known === undefined) return null
  if (stored === known) return null
  if (stored === open) return null
  return stored
}

/**
 * The layer a jump's way back should lead to, or `null` when there is none worth offering.
 *
 * @param chosen the layer the user last opened themselves. It survives a whole chain of
 *   jumps, which is the point: after A -> B -> C the way back is still A, not B.
 * @param jumpedTo where the jump just landed. Equal to `chosen` when someone moved the
 *   view away and then back again -- offering a way back to where the user already is
 *   would be an empty gesture.
 */
export function layerJumpBackTarget(chosen: string | null, jumpedTo: string): string | null {
  return chosen !== null && chosen !== jumpedTo ? chosen : null
}

/**
 * A restored selection with every fid that no longer exists removed -- see CONTRACT.md
 * rule 3, "Die Auswahl zeigt auf gelöschte Objekte": vanished ones drop silently, the
 * caller only needs to say something when nothing of the restored selection survives.
 */
export function survivingSelection(
  restored: readonly number[],
  existingFids: ReadonlySet<number>,
): number[] {
  return restored.filter((fid) => existingFids.has(fid))
}

/**
 * Whether a restored filter/search is worth flagging as still hiding rows -- see
 * CONTRACT.md rule 1, "Ein gespeicherter Filter versteckt Daten". Equal counts mean the
 * restriction matched everything the layer has, which hides nothing worth a hint over.
 */
export function restoredQueryHidesData(matchedCount: number, layerFeatureCount: number): boolean {
  return matchedCount < layerFeatureCount
}
