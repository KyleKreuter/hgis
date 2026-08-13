import { describe, expect, it } from 'vitest'
import { BASEMAPS, attributionText, resolveBasemap } from '../basemap'
import { combinedAttributionParts } from '../geoportalAttribution'
import { buildFurniture, type FurnitureInput } from './furniture'
import { exportZoom } from './exportView'
import { computeImageSize, findPageChoice } from './pageFormat'

const HAMBURG = 53.55

function input(overrides: Partial<FurnitureInput> = {}): FurnitureInput {
  return {
    title: 'Baumkataster Wandsbek',
    centerLat: HAMBURG,
    zoom: 14,
    bearing: 0,
    pitch: 0,
    cssWidth: 1122,
    attribution: resolveBasemap('osm').attribution,
    ...overrides,
  }
}

describe('buildFurniture: Titel', () => {
  it('übernimmt den Titel', () => {
    expect(buildFurniture(input()).title).toBe('Baumkataster Wandsbek')
  })

  it('zeichnet ohne Titel nichts statt eines leeren Kastens', () => {
    expect(buildFurniture(input({ title: '   ' })).title).toBeNull()
  })
})

describe('buildFurniture: Nordpfeil', () => {
  it('bleibt weg, solange Norden oben und die Karte flach ist', () => {
    expect(buildFurniture(input({ bearing: 0, pitch: 0 })).northArrow).toBeNull()
  })

  it('erscheint bei gedrehter Karte und nennt die Drehung', () => {
    expect(buildFurniture(input({ bearing: 45 })).northArrow).toEqual({ bearing: 45 })
  })

  it('erscheint auch bei geneigter Karte ohne Drehung', () => {
    expect(buildFurniture(input({ pitch: 40 })).northArrow).not.toBeNull()
  })

  it('wertet einen Rundungsrest nicht als Drehung', () => {
    expect(buildFurniture(input({ bearing: 0.001, pitch: 0.001 })).northArrow).toBeNull()
  })

  it('führt eine Drehung um 350 Grad auf minus 10 Grad zurück', () => {
    expect(buildFurniture(input({ bearing: 350 })).northArrow).toEqual({ bearing: -10 })
  })
})

describe('buildFurniture: Quellenangabe', () => {
  /**
   * A licence term, not a decoration (CONTRACT.md 13.1). There is no option on the input
   * that could leave it out, and this test walks the whole catalog so a background map
   * added later cannot arrive without one either.
   */
  it('trägt die Angabe jeder Hintergrundkarte, die eine verlangt', () => {
    for (const basemap of BASEMAPS) {
      const plan = buildFurniture(input({ attribution: basemap.attribution }))
      expect(plan.attribution).toBe(attributionText(basemap.attribution))
      if (basemap.attribution.length > 0) {
        expect(plan.attribution.length).toBeGreaterThan(0)
      }
    }
  })

  it('nennt OpenStreetMap wörtlich', () => {
    expect(buildFurniture(input()).attribution).toBe('© OpenStreetMap contributors')
  })

  it('nimmt die Angaben sichtbarer Geoportal-Layer mit auf', () => {
    const attribution = combinedAttributionParts(resolveBasemap('osm').attribution, [
      {
        attribution: 'Freie und Hansestadt Hamburg, LGV',
        licenseUrl: 'https://www.govdata.de/dl-de/by-2-0',
      },
    ])

    const plan = buildFurniture(input({ attribution }))

    expect(plan.attribution).toContain('OpenStreetMap')
    expect(plan.attribution).toContain('Freie und Hansestadt Hamburg, LGV')
  })
})

describe('buildFurniture: Maßstabsbalken', () => {
  it('bleibt innerhalb eines Viertels der Bildbreite', () => {
    const plan = buildFurniture(input({ cssWidth: 400 }))

    expect(plan.scaleBar).not.toBeNull()
    expect(plan.scaleBar!.widthCssPx).toBeLessThanOrEqual(100)
  })

  it('deckelt den Balken auch auf einer sehr breiten Seite', () => {
    const plan = buildFurniture(input({ cssWidth: 4000 }))

    expect(plan.scaleBar!.widthCssPx).toBeLessThanOrEqual(200)
  })

  it('nennt eine runde Strecke mit Einheit', () => {
    expect(buildFurniture(input()).scaleBar!.label).toMatch(/^\d+(\.\d+)? (m|km)$/)
  })

  /**
   * The trap CONTRACT.md 13.1 names: the page has its own scale, and copying the screen's
   * bar would look plausible while being wrong. A4 landscape at 300 dpi from a 900 x 600
   * map panel zooms in by a third of a level, and the bar has to follow.
   */
  it('rechnet mit dem Zoom des Exports, nicht mit dem des Bildschirms', () => {
    const screen = { width: 900, height: 600 }
    const size = computeImageSize(findPageChoice('a4-landscape'), 300, screen)
    const screenZoom = 14
    const pageZoom = exportZoom(screenZoom, screen, {
      width: size.cssWidth,
      height: size.cssHeight,
    })

    expect(pageZoom).not.toBeCloseTo(screenZoom, 3)

    const fromScreen = buildFurniture(input({ zoom: screenZoom, cssWidth: size.cssWidth }))
    const fromPage = buildFurniture(input({ zoom: pageZoom, cssWidth: size.cssWidth }))

    expect(fromPage.scaleBar!.widthCssPx).not.toBeCloseTo(fromScreen.scaleBar!.widthCssPx, 1)
  })

  it('zeigt weiter nördlich eine kürzere Strecke für dieselbe Balkenbreite', () => {
    const equator = buildFurniture(input({ centerLat: 0 }))
    const north = buildFurniture(input({ centerLat: 70 }))

    expect(north.scaleBar!.label).not.toBe(equator.scaleBar!.label)
  })
})
