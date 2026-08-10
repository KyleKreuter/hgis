import { describe, expect, it } from 'vitest'
import { computeInitialView } from './initialView'
import { GERMANY_VIEW } from './basemap'

describe('computeInitialView', () => {
  it('bevorzugt ein gespeichertes center/zoom', () => {
    const view = computeInitialView({ center: [10, 50], zoom: 8, extent: [0, 0, 1, 1] })
    expect(view).toEqual({ center: [10, 50], zoom: 8 })
  })

  it('fällt auf den Layer-Extent zurück, wenn kein center/zoom gespeichert ist', () => {
    const view = computeInitialView({ center: null, zoom: null, extent: [9.9, 53.4, 10.1, 53.6] })
    expect(view).toEqual({ bounds: [[9.9, 53.4], [10.1, 53.6]] })
  })

  it('fällt auf Deutschland zurück, wenn weder center/zoom noch extent vorhanden sind', () => {
    const view = computeInitialView({ center: null, zoom: null, extent: null })
    expect(view).toEqual(GERMANY_VIEW)
  })
})
