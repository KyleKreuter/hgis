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
