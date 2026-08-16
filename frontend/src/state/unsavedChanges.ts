import { formatCount } from '@/lib/format'

/**
 * Whether leaving right now -- another layer, the project list, closing the tab -- would
 * silently throw work away. Pulled out of `routes/projects.$projectId.tsx` as plain
 * functions, deliberately free of any router or store import, so the counting and the
 * wording can be tested without a router, a mounted component or a zustand store (this
 * project has no jsdom/@testing-library setup, see `useEditSession.test.ts`).
 *
 * The map's draft buffer (`state/editing.ts`) and the table's cell edits
 * (`table/tableEditSession.ts`) are two independent buffers -- only one of them is ever
 * active at a time (CONTRACT.md), but a navigation guard has to watch both regardless of
 * which one happens to be running. The caller supplies both counts already read off their
 * own store; this module only combines and phrases them.
 */

/** How many unsaved changes exist right now, across both edit modes combined. */
export function totalUnsavedChanges(mapChanges: number, tableChanges: number): number {
  return mapChanges + tableChanges
}

/** Whether a navigation guard needs to ask before continuing. */
export function hasUnsavedChanges(mapChanges: number, tableChanges: number): boolean {
  return totalUnsavedChanges(mapChanges, tableChanges) > 0
}

/**
 * Everything the user would lose by leaving this layer right now.
 *
 * The third field is why this type exists. A shape whose corners are set but which is not
 * closed yet is in the drawing tool alone -- it reaches the buffer only when terra-draw
 * says `finish` (see `DrawController`). Counting it as a change would make the toolbar say
 * "1 ungespeicherte Änderung" for three clicks that are not a polygon yet; not counting it
 * at all was worse: switching layers threw the half-drawn shape away without a word.
 */
export interface UnsavedWork {
  mapChanges: number
  tableChanges: number
  /** A shape is half-drawn: corners set, not closed. See `editing.ts`'s `sketching`. */
  sketching: boolean
}

/**
 * Whether leaving now would cost the user something -- the question every guard actually
 * asks, as opposed to {@link hasUnsavedChanges}, which only counts what is in a buffer.
 */
export function hasUnsavedWork({ mapChanges, tableChanges, sketching }: UnsavedWork): boolean {
  return sketching || hasUnsavedChanges(mapChanges, tableChanges)
}

/** What a half-drawn shape is called wherever the user is told about one. One name, everywhere. */
const SKETCH = 'eine angefangene Zeichnung'

/**
 * Subject and verb of a discard sentence, e.g. "3 ungespeicherte Änderungen gehen" --
 * the caller adds " verloren, wenn …". Both halves come from here because German makes
 * the verb depend on the subject, and the subject is not always a count.
 *
 * Three shapes, because all three occur: only changes, only a half-drawn shape, or both.
 */
export function describeUnsavedWork({ mapChanges, tableChanges, sketching }: UnsavedWork): string {
  const count = totalUnsavedChanges(mapChanges, tableChanges)
  if (!sketching) {
    return `${describeUnsavedChanges(count)} ${unsavedChangesVerb(count)}`
  }
  if (count === 0) {
    return `${SKETCH.charAt(0).toUpperCase()}${SKETCH.slice(1)} geht`
  }
  // Two subjects joined by "und" are plural regardless of the count, so no verb lookup.
  return `${describeUnsavedChanges(count)} und ${SKETCH} gehen`
}

/**
 * "3 ungespeicherte Änderungen" / "1 ungespeicherte Änderung" -- the noun phrase a discard
 * confirmation states outright, the same way `DeleteLayerDialog` states its feature count
 * instead of saying "some data".
 */
export function describeUnsavedChanges(count: number): string {
  return `${formatCount(count)} ungespeicherte ${count === 1 ? 'Änderung' : 'Änderungen'}`
}

/**
 * "gehen" for more than one change, "geht" for exactly one -- matches the pronoun "Sie"
 * (referring back to "Änderung(en)", not the polite form of address) that every discard
 * sentence in the workspace builds its second half on.
 */
export function unsavedChangesVerb(count: number): 'geht' | 'gehen' {
  return count === 1 ? 'geht' : 'gehen'
}
