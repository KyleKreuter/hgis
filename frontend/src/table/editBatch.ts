import type { EditUpdate } from '@/api/edits'

/**
 * Turns the edit buffer into the `updates` list `POST /api/layers/{layerId}/edits`
 * expects (CONTRACT.md). Only changed columns go into `properties` -- a column the user
 * never touched is simply absent, which is what tells the backend to leave it alone.
 *
 * No `geometry` on any entry: the table only ever changes attributes.
 */
export function buildUpdates(
  edits: ReadonlyMap<number, Record<string, unknown>>,
  rowVersions: ReadonlyMap<number, string>,
): EditUpdate[] {
  return Array.from(edits.entries()).map(([fid, properties]) => ({
    fid,
    rowVersion: rowVersions.get(fid),
    properties,
  }))
}
