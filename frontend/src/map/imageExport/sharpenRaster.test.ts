import type { StyleSpecification } from 'maplibre-gl'
import { describe, expect, test } from 'vitest'
import { extraZoomLevels, sharpenRasterSources } from './sharpenRaster'

function styleWith(sources: StyleSpecification['sources']): StyleSpecification {
  return { version: 8, sources, layers: [] }
}

const OSM = {
  type: 'raster' as const,
  tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
  tileSize: 256,
  maxzoom: 19,
}

describe('extraZoomLevels', () => {
  test('gibt bei Bildschirmauflösung nichts dazu', () => {
    expect(extraZoomLevels(1)).toBe(0)
  })

  test('gibt bei doppelter Auflösung eine Stufe dazu', () => {
    expect(extraZoomLevels(2)).toBe(1)
  })

  test('rundet 3,125 auf zwei Stufen', () => {
    // A4 bei 300 dpi. Abrunden liesse die Karte sichtbar weicher als die Daten darauf.
    expect(extraZoomLevels(3.125)).toBe(2)
  })

  test('deckelt bei zwei Stufen', () => {
    // Sonst waeren es 64-fach so viele Kacheln von gespendeten Servern.
    expect(extraZoomLevels(16)).toBe(2)
  })

  test('nimmt unsinnige Werte nicht an', () => {
    expect(extraZoomLevels(0)).toBe(0)
    expect(extraZoomLevels(-2)).toBe(0)
    expect(extraZoomLevels(Number.NaN)).toBe(0)
  })
})

describe('sharpenRasterSources', () => {
  test('holt bei doppelter Auflösung Kacheln einer Stufe tiefer', () => {
    const out = sharpenRasterSources(styleWith({ osm: OSM }), 2)

    expect((out.sources.osm as { tileSize: number }).tileSize).toBe(128)
  })

  test('holt bei 300 dpi zwei Stufen tiefer', () => {
    const out = sharpenRasterSources(styleWith({ osm: OSM }), 3.125)

    expect((out.sources.osm as { tileSize: number }).tileSize).toBe(64)
  })

  test('lässt den Stil bei Bildschirmauflösung unangetastet', () => {
    const style = styleWith({ osm: OSM })

    expect(sharpenRasterSources(style, 1)).toBe(style)
  })

  test('fasst Vektorquellen nicht an', () => {
    // Vektordaten werden ohnehin in Zielauflösung neu gezeichnet.
    const vector = { type: 'vector' as const, tiles: ['https://example.org/{z}/{x}/{y}.pbf'] }
    const out = sharpenRasterSources(styleWith({ layer1: vector, osm: OSM }), 4)

    expect(out.sources.layer1).toEqual(vector)
    expect((out.sources.osm as { tileSize: number }).tileSize).toBe(64)
  })

  test('fasst Höhenraster nicht an', () => {
    // raster-dem wird abgetastet, nicht gezeichnet -- feinere Hoehen aendern die Form.
    const dem = { type: 'raster-dem' as const, tiles: ['https://example.org/{z}/{x}/{y}.png'], tileSize: 256 }
    const out = sharpenRasterSources(styleWith({ gelaende: dem }), 4)

    expect(out.sources.gelaende).toEqual(dem)
  })

  test('lässt eine Quelle in Ruhe, die dabei zu klein würde', () => {
    const small = { type: 'raster' as const, tiles: ['https://example.org/{z}/{x}/{y}.png'], tileSize: 64 }
    const out = sharpenRasterSources(styleWith({ klein: small }), 4)

    expect(out.sources.klein).toEqual(small)
  })

  test('nimmt MapLibres Vorgabe an, wenn die Quelle keine Kachelgröße nennt', () => {
    const bare = { type: 'raster' as const, tiles: ['https://example.org/{z}/{x}/{y}.png'] }
    const out = sharpenRasterSources(styleWith({ q: bare }), 2)

    expect((out.sources.q as { tileSize: number }).tileSize).toBe(256)
  })

  test('verändert den übergebenen Stil nicht', () => {
    // Der Stil gehoert der sichtbaren Karte. Waere er veraendert, wuerde ein Export die
    // Bildschirmkarte auf die vierfache Kachelzahl umstellen.
    const style = styleWith({ osm: OSM })
    sharpenRasterSources(style, 4)

    expect((style.sources.osm as { tileSize: number }).tileSize).toBe(256)
  })
})
