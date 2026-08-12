import { beforeEach, describe, expect, it } from 'vitest'
import { isStaleEditSession, resolveSelectedFeature } from './useEditSession'
import { countChanges, dirtyFids, useEditing, type DraftFeature } from '@/state/editing'

const POINT: GeoJSON.Geometry = { type: 'Point', coordinates: [9.98, 53.54] }

function loaded(fid: number): DraftFeature {
  return { fid, geometry: POINT, properties: { strasse: 'Alt' }, rowVersion: '42' }
}

/**
 * `resolveSelectedFeature` is pulled out of the hook precisely so it can be exercised
 * here without rendering a component -- this project has no jsdom/@testing-library setup
 * (vitest.config.ts runs with `environment: 'node'` and only picks up `*.test.ts`), and
 * adding one just for this would be a disproportionate amount of new test infrastructure
 * for a single regression case.
 */
describe('resolveSelectedFeature', () => {
  beforeEach(() => {
    useEditing.getState().end()
  })

  it('shows the loaded feature for a plain click, before any edit', () => {
    // This is the bug: selecting an existing feature writes nothing into the buffer (by
    // design -- a click must not count as a change, see the `dirtyFids` tests in
    // state/editing.test.ts), so the old lookup of `buffer.creates ?? buffer.updates ??
    // null` had nothing to find and the attribute form showed "Kein Objekt ausgewählt."
    const empty = useEditing.getState().buffer
    const feature = resolveSelectedFeature(empty, 7, loaded(7))

    expect(feature).toEqual(loaded(7))
  })

  it('never writes a plain selection into the buffer', () => {
    resolveSelectedFeature(useEditing.getState().buffer, 7, loaded(7))

    // The toolbar's change counter and the tile filter both read off the buffer -- a
    // selection reaching either would move the counter or hide the feature's tile for no
    // reason.
    expect(countChanges(useEditing.getState().buffer)).toBe(0)
    expect(dirtyFids(useEditing.getState().buffer)).toEqual([])
  })

  it('prefers the buffer once the feature has actually been edited', () => {
    useEditing.getState().updateProperties(loaded(7), { strasse: 'Neu' })

    const feature = resolveSelectedFeature(useEditing.getState().buffer, 7, loaded(7))

    // Whoever already typed something sees their own input, not the server state that
    // `selectedOriginal` still holds.
    expect(feature?.properties).toEqual({ strasse: 'Neu' })
  })

  it('shows a freshly drawn feature straight from buffer.creates', () => {
    const fid = useEditing.getState().addFeature(POINT)

    // No loaded copy for a feature that was drawn, not loaded -- `null` is what
    // DrawController actually passes in this case.
    const feature = resolveSelectedFeature(useEditing.getState().buffer, fid, null)

    expect(feature?.fid).toBe(fid)
  })

  it('is null when nothing is selected', () => {
    const feature = resolveSelectedFeature(useEditing.getState().buffer, null, loaded(7))

    expect(feature).toBeNull()
  })
})

describe('isStaleEditSession', () => {
  it('erkennt eine Sitzung, die auf dem vorigen Layer stehen geblieben ist', () => {
    // Der belegte Fehler: bei leerem Puffer greift `leaveGuard` nicht, der Layerwechsel
    // geht durch, und `useEditing.layerId` zeigt weiter auf den alten Layer. Genau darauf
    // sperren `DeleteLayerDialog` und `ManageFieldsDialog`.
    expect(isStaleEditSession('a', 'b')).toBe(true)
  })

  it('erkennt eine Sitzung, deren Layer gar nicht mehr geöffnet ist', () => {
    expect(isStaleEditSession('a', null)).toBe(true)
  })

  it('lässt eine Sitzung auf dem aktiven Layer in Ruhe', () => {
    expect(isStaleEditSession('a', 'a')).toBe(false)
  })

  it('meldet ohne laufende Sitzung nichts -- auch nicht beim Layerwechsel', () => {
    expect(isStaleEditSession(null, 'b')).toBe(false)
    expect(isStaleEditSession(null, null)).toBe(false)
  })
})
