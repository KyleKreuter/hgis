import { describe, expect, it } from 'vitest'
import { exportZoom, MAX_EXPORT_ZOOM } from './exportView'

const SCREEN = { width: 900, height: 600 }

describe('exportZoom', () => {
  it('lässt den Zoom unverändert, wenn das Bild so groß ist wie die Karte', () => {
    expect(exportZoom(14, SCREEN, SCREEN)).toBeCloseTo(14, 6)
  })

  it('zoomt eine Zoomstufe hinein, wenn das Bild doppelt so groß ist', () => {
    expect(exportZoom(14, SCREEN, { width: 1800, height: 1200 })).toBeCloseTo(15, 6)
  })

  it('zoomt eine Zoomstufe heraus, wenn das Bild halb so groß ist', () => {
    expect(exportZoom(14, SCREEN, { width: 450, height: 300 })).toBeCloseTo(13, 6)
  })

  /**
   * The rule that keeps the screen view inside the page: a portrait page next to a wide
   * map panel has room to spare across but not down, so the height decides.
   */
  it('richtet sich nach der knapperen der beiden Kanten', () => {
    // Twice as wide, but the same height -- so nothing is gained.
    expect(exportZoom(14, SCREEN, { width: 1800, height: 600 })).toBeCloseTo(14, 6)
  })

  it('bleibt bei einem noch nicht aufgebauten Kartenfenster beim Bildschirmzoom', () => {
    expect(exportZoom(14, { width: 0, height: 0 }, { width: 1122, height: 793 })).toBe(14)
  })

  it('überschreitet die höchste Zoomstufe nicht', () => {
    expect(exportZoom(21, SCREEN, { width: 9000, height: 6000 })).toBe(MAX_EXPORT_ZOOM)
  })
})
