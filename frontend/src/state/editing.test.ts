import { beforeEach, describe, expect, it, vi } from 'vitest'
import { countChanges, dirtyFids, useEditing, type DraftFeature } from './editing'

const POINT: GeoJSON.Geometry = { type: 'Point', coordinates: [9.98, 53.54] }
const OTHER_POINT: GeoJSON.Geometry = { type: 'Point', coordinates: [10.0, 53.6] }

function store() {
  return useEditing.getState()
}

function existing(fid: number): DraftFeature {
  return { fid, geometry: POINT, properties: { strasse: 'Alt' }, rowVersion: '42' }
}

describe('edit buffer', () => {
  beforeEach(() => {
    store().end()
    vi.useRealTimers()
  })

  it('gives new features negative ids', () => {
    const first = store().addFeature(POINT)
    const second = store().addFeature(POINT)

    // The sign alone says whether a feature exists on the server.
    expect(first).toBeLessThan(0)
    expect(second).toBeLessThan(first)
  })

  it('does not reuse a temporary id after undo', () => {
    const first = store().addFeature(POINT)
    store().undo()
    const second = store().addFeature(POINT)

    expect(second).not.toBe(first)
  })

  it('counts every kind of pending change', () => {
    store().addFeature(POINT)
    store().updateProperties(existing(1), { strasse: 'Neu' })
    store().removeFeature(2)

    expect(countChanges(store().buffer)).toBe(3)
  })

  it('reports touched existing features so the tiles can hide them', () => {
    store().addFeature(POINT)
    store().updateGeometry(existing(7), OTHER_POINT)
    store().removeFeature(9)

    // The new feature is not in the list: it has no tile to hide.
    expect(dirtyFids(store().buffer).sort()).toEqual([7, 9])
  })

  describe('dirtyFids and what actually changed', () => {
    it('leaves a feature visible when only its properties changed', () => {
      store().updateProperties(existing(3), { strasse: 'Neu' })

      // Its tile geometry is still correct -- nothing was drawn to replace it.
      expect(dirtyFids(store().buffer)).toEqual([])
    })

    it('hides a feature once its geometry changed', () => {
      store().updateGeometry(existing(4), OTHER_POINT)

      expect(dirtyFids(store().buffer)).toEqual([4])
    })

    it('hides a feature whose geometry and properties both changed', () => {
      store().updateGeometry(existing(5), OTHER_POINT)
      store().updateProperties(existing(5), { strasse: 'Neu' })

      expect(dirtyFids(store().buffer)).toEqual([5])
    })

    it('keeps a feature hidden once its geometry changed, even after a later attribute edit', () => {
      // Order reversed compared to the test above: geometry first, then properties.
      store().updateGeometry(existing(6), OTHER_POINT)
      store().updateProperties(existing(6), { strasse: 'Neu' })

      expect(dirtyFids(store().buffer)).toEqual([6])
    })

    it('does not hide a new feature no matter what changed on it', () => {
      const fid = store().addFeature(POINT)
      store().updateGeometry({ fid, geometry: POINT, properties: {} }, OTHER_POINT)

      expect(dirtyFids(store().buffer)).toEqual([])
    })

    it('hides a deleted feature regardless of what was edited on it before', () => {
      store().updateProperties(existing(8), { strasse: 'Neu' })
      store().removeFeature(8)

      expect(dirtyFids(store().buffer)).toEqual([8])
    })

    it('unhides a feature again once its geometry edit is undone', () => {
      store().updateGeometry(existing(7), OTHER_POINT)
      expect(dirtyFids(store().buffer)).toEqual([7])

      store().undo()

      // The update entry is gone entirely, not merely stripped of the flag -- it was
      // the only change recorded for this feature.
      expect(store().buffer.updates).toEqual({})
      expect(dirtyFids(store().buffer)).toEqual([])
    })
  })

  it('forgets a new feature entirely when it is deleted', () => {
    const fid = store().addFeature(POINT)
    store().removeFeature(fid)

    expect(store().buffer.creates).toEqual({})
    expect(store().buffer.deletes).toEqual([])
  })

  it('signals the drawing surface when a feature is deleted', () => {
    const before = store().historyNonce
    store().removeFeature(2)
    // Same channel undo uses: without it a toolbar delete would leave the shape on the map.
    expect(store().historyNonce).toBe(before + 1)
  })

  describe('undo and redo', () => {
    it('takes back one change and puts it back', () => {
      store().addFeature(POINT)
      expect(countChanges(store().buffer)).toBe(1)

      store().undo()
      expect(countChanges(store().buffer)).toBe(0)

      store().redo()
      expect(countChanges(store().buffer)).toBe(1)
    })

    it('walks back several changes in order', () => {
      store().addFeature(POINT)
      store().updateProperties(existing(1), { strasse: 'Neu' })
      store().removeFeature(2)

      store().undo()
      expect(store().buffer.deletes).toEqual([])
      store().undo()
      expect(store().buffer.updates).toEqual({})
      store().undo()
      expect(store().buffer.creates).toEqual({})
    })

    it('drops the redo stack once a new change is made', () => {
      store().addFeature(POINT)
      store().undo()
      expect(store().redoStack).toHaveLength(1)

      store().addFeature(OTHER_POINT)
      // The redone future belongs to a history that no longer happened.
      expect(store().redoStack).toHaveLength(0)
    })

    it('does nothing when there is nothing to undo', () => {
      expect(() => store().undo()).not.toThrow()
      expect(() => store().redo()).not.toThrow()
    })

    it('collapses a run of geometry changes into one step', () => {
      const feature = existing(7)
      // What dragging a vertex produces: a change per mouse move.
      store().updateGeometry(feature, POINT)
      store().updateGeometry(feature, OTHER_POINT)
      store().updateGeometry(feature, POINT)

      expect(store().undoStack).toHaveLength(1)

      store().undo()
      expect(store().buffer.updates).toEqual({})
    })

    it('keeps changes to different features apart', () => {
      store().updateGeometry(existing(7), POINT)
      store().updateGeometry(existing(8), POINT)

      expect(store().undoStack).toHaveLength(2)
    })

    it('does not collapse across a pause', () => {
      vi.useFakeTimers()
      const feature = existing(7)
      store().updateGeometry(feature, POINT)
      vi.advanceTimersByTime(2000)
      store().updateGeometry(feature, OTHER_POINT)

      expect(store().undoStack).toHaveLength(2)
    })
  })

  it('clears the history on reset so undo cannot reach persisted state', () => {
    store().addFeature(POINT)
    store().reset()

    expect(countChanges(store().buffer)).toBe(0)
    expect(store().undoStack).toHaveLength(0)
    expect(store().redoStack).toHaveLength(0)
  })
})
