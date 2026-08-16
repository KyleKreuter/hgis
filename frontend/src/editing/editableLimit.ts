/**
 * How many existing features the editor loads into one session, and the server ceiling it
 * has to stay under.
 *
 * Its own module, and not a constant inside `DrawController`, for the same reason
 * `renderLimit.ts` is one: the number is only correct in relation to something on the
 * other side of the wire, and that relation is worth a test of its own.
 */

/**
 * The largest `size` the feature endpoint serves -- `FeatureQueryService.MAX_PAGE_SIZE`.
 *
 * Asking for more is a 400, not a smaller page. That used to be the other way round: the
 * server trimmed silently, so a request for 2000 came back with 1000 rows and nothing that
 * said so.
 */
export const SERVER_PAGE_LIMIT = 1000

/**
 * How many existing features are loaded into the editor at once.
 *
 * Above this the viewport holds more than anyone edits by hand, and every one of them
 * would become a draggable object with its own vertices. The limit is announced rather
 * than silently applied -- plan section D.1 makes the same call for snapping.
 *
 * It is sent straight to the server as `size`, so it may never exceed
 * {@link SERVER_PAGE_LIMIT}. It did, at 2000, and both halves of the limit were wrong at
 * once: the request now fails outright, and before the server started refusing, a viewport
 * of 1500 objects loaded 1000 of them while `totalCount` stayed under the threshold this
 * same number sets -- so the warning never fired and snapping attached to whichever two
 * thirds had arrived. The number that reaches the server and the number the warning is
 * measured against have to be the same one.
 */
export const MAX_EDITABLE = 1000
