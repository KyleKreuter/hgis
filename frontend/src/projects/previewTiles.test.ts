import { describe, expect, it } from 'vitest'
import { previewTilesFor } from './previewTiles'

describe('previewTilesFor', () => {
  it('liefert ein leeres Feld ohne center und ohne extent', () => {
    expect(
      previewTilesFor({ center: null, zoom: null, extent: null, basemap: 'osm' }),
    ).toEqual([])
  })

  it('liefert ein leeres Feld ohne Ortsangabe, auch wenn zoom gesetzt ist', () => {
    expect(
      previewTilesFor({ center: null, zoom: 8, extent: null, basemap: 'osm' }),
    ).toEqual([])
  })

  it('liefert ein leeres Feld bei basemap: none, obwohl ein center vorliegt', () => {
    expect(
      previewTilesFor({ center: [10, 50], zoom: 10, extent: null, basemap: 'none' }),
    ).toEqual([])
  })

  it('begrenzt den Zoom auf das, was OSM hergibt', () => {
    const tiles = previewTilesFor({ center: [0, 0], zoom: 25, extent: null, basemap: 'osm' })

    expect(tiles).toHaveLength(4)
    expect(tiles.every((tile) => tile.z === 19)).toBe(true)
  })

  it('begrenzt den Zoom auf das, was OpenTopoMap hergibt', () => {
    const tiles = previewTilesFor({
      center: [0, 0],
      zoom: 25,
      extent: null,
      basemap: 'opentopo',
    })

    expect(tiles).toHaveLength(4)
    expect(tiles.every((tile) => tile.z === 17)).toBe(true)
  })

  it('hält die Kachelnummern an den Rändern der Welt im gültigen Bereich', () => {
    // Nordwest-Ecke, Zoom 0: nur eine einzige Kachel existiert überhaupt (0,0). Die
    // Rohrechnung landet hier deutlich außerhalb 0..2^z - 1 und muss geklemmt werden.
    const tiles = previewTilesFor({
      center: [-179.99, 89.9],
      zoom: 0,
      extent: null,
      basemap: 'osm',
    })

    expect(tiles).toHaveLength(4)
    for (const tile of tiles) {
      expect(tile.z).toBe(0)
      expect(tile.x).toBeGreaterThanOrEqual(0)
      expect(tile.x).toBeLessThanOrEqual(2 ** tile.z - 1)
      expect(tile.y).toBeGreaterThanOrEqual(0)
      expect(tile.y).toBeLessThanOrEqual(2 ** tile.z - 1)
    }
  })

  it('errechnet aus einer bekannten Koordinate die erwartete Kachel', () => {
    // Nullmeridian/Äquator bei Zoom 4: die Rechnung landet exakt auf der Kachelgrenze
    // (x = y = 8), das Raster liegt also symmetrisch um den Punkt: 7/8 in beide
    // Richtungen, links oben beginnend.
    const tiles = previewTilesFor({ center: [0, 0], zoom: 4, extent: null, basemap: 'osm' })

    expect(tiles).toEqual([
      { x: 7, y: 7, z: 4, url: 'https://tile.openstreetmap.org/4/7/7.png' },
      { x: 8, y: 7, z: 4, url: 'https://tile.openstreetmap.org/4/8/7.png' },
      { x: 7, y: 8, z: 4, url: 'https://tile.openstreetmap.org/4/7/8.png' },
      { x: 8, y: 8, z: 4, url: 'https://tile.openstreetmap.org/4/8/8.png' },
    ])
  })

  it('bestimmt den Zoom aus extent, wenn kein zoom vorliegt', () => {
    // Ein grober Umriss (10 Grad) braucht einen deutlich niedrigeren Zoom als ein enger
    // (0.1 Grad), damit beide noch ins 2x2-Raster passen.
    const wide = previewTilesFor({
      center: null,
      zoom: null,
      extent: [-5, -5, 5, 5],
      basemap: 'osm',
    })
    const narrow = previewTilesFor({
      center: null,
      zoom: null,
      extent: [-0.05, -0.05, 0.05, 0.05],
      basemap: 'osm',
    })

    expect(wide[0].z).toBeLessThan(narrow[0].z)
  })

  /**
   * Liegen center UND extent vor, gewinnt extent für beides -- Mittelpunkt eingeschlossen.
   * Ein center aus einer alten Ansicht, kombiniert mit einem aus extent errechneten Zoom,
   * kann sonst weder den einen noch den anderen Ort richtig zeigen: ein willkürlicher
   * Ausschnitt, der nichts über die Daten des Projekts sagt.
   */
  it('zeigt bei center UND extent den extent, nicht das center', () => {
    const hamburg: [number, number] = [9.99, 53.55]
    const munich: [number, number, number, number] = [11.3, 48.0, 11.7, 48.3]

    const withFarCenter = previewTilesFor({
      center: hamburg,
      zoom: 10,
      extent: munich,
      basemap: 'osm',
    })
    const extentOnly = previewTilesFor({
      center: null,
      zoom: null,
      extent: munich,
      basemap: 'osm',
    })

    expect(withFarCenter).toEqual(extentOnly)
  })

  it('nutzt center und zoom weiterhin, wenn kein extent vorliegt', () => {
    const withoutZoom = previewTilesFor({
      center: [0, 0],
      zoom: null,
      extent: null,
      basemap: 'osm',
    })
    const withDefaultZoom = previewTilesFor({
      center: [0, 0],
      zoom: 12,
      extent: null,
      basemap: 'osm',
    })

    // Ohne zoom greift die Vorgabe 12 -- deckungsgleich mit einem Projekt, das genau
    // diesen Zoom gespeichert hat.
    expect(withoutZoom).toEqual(withDefaultZoom)
    expect(withoutZoom[0].z).toBe(12)
  })
})
